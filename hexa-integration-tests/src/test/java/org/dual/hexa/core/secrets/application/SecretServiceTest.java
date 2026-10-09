package org.dual.hexa.core.secrets.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.secrets.domain.Secret;
import org.dual.hexa.core.events.domain.SystemEvent;
import org.dual.hexa.core.events.domain.SystemEventSeverity;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.events.port.out.ISystemEventStore;
import org.dual.hexa.core.secrets.domain.SecretException;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.out.ISecretStore;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contro il DB in-memory (i warning passano da REQUIRES_NEW e committano davvero: nessun @Transactional, pulizia a mano) e con la
 * chiave di cifratura di test (pom.xml). Il servizio sotto test e' costruito a mano con un Clock fisso (2026-10-01).
 */
@SpringBootTest
class SecretServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Autowired
    private ISecretStore repository;
    @Autowired
    private ISystemEventStore eventRepository;
    @Autowired
    private SecretCipher cipher;
    @Autowired
    private ISystemEvents events;
    @Autowired
    private Messages messages;

    /** I tipi dell'app (e uno `managed` come quelli dei moduli): senza catalogo un tipo e' sconosciuto e rifiutato. */
    private static final ISecretTypeCatalog TEST_TYPES = () -> List.of(new SecretType("HUGGINGFACE", "x"), new SecretType("CIVITAI", "x"),
            new SecretType("MODULE_OWNED", "x", true));

    private SecretService service;

    /** I test condividono il DB: i token e gli eventi di questa classe non devono restare per le altre (es. quelle che contano i token HuggingFace). */
    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        eventRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        eventRepository.deleteAll();
        service = new SecretService(repository, cipher, events, messages, 15,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()), List.of(TEST_TYPES));
    }

    private Secret saved(String type, String name, String plain, LocalDate expires) {
        return repository.save(new Secret(type, name, cipher.encrypt(plain), plain.substring(plain.length() - 4), expires, Instant.now()));
    }

    private void assertRejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(SecretException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.REJECTED);
    }

    @Test
    void createStoresTheTokenEncryptedWithOnlyAHintInTheClear() {
        var view = service.create("HUGGINGFACE", "  Personale ", " hf_secret_abcd ", null);

        assertThat(view.name()).isEqualTo("Personale");
        assertThat(view.hint()).isEqualTo("abcd");
        Secret row = repository.findById(view.id()).orElseThrow();
        assertThat(new String(row.getValueEncrypted(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("hf_secret");
        assertThat(row.toString()).doesNotContain("hf_secret").doesNotContain("abcd");
        assertThat(service.resolve(view.id(), "HUGGINGFACE")).isEqualTo("hf_secret_abcd");
    }

    @Test
    void validationRejectsBlankDuplicateAndOversizedInputsButAllowsTheSameNameOnAnotherProvider() {
        service.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null);

        assertRejected(() -> service.create("HUGGINGFACE", "  ", "x1234", null));
        assertRejected(() -> service.create("HUGGINGFACE", "x".repeat(ISecrets.MAX_NAME + 1), "x1234", null));
        assertRejected(() -> service.create("HUGGINGFACE", "personale", "x1234", null)); // maiuscole/minuscole
        assertRejected(() -> service.create("HUGGINGFACE", "Altro", "", null));
        assertRejected(() -> service.create("HUGGINGFACE", "Altro", "t".repeat(ISecrets.MAX_VALUE + 1), null));
        assertRejected(() -> service.create(null, "Altro", "x1234", null));
        assertRejected(() -> service.create("HUGGINGFACE", "Altro", "x1234", TODAY.minusDays(1)));
        assertThat(service.create("CIVITAI", "Personale", "cv_secret_wxyz", null).id()).isNotNull();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void updateWithABlankTokenKeepsItAndWithATokenReplacesIt() {
        var created = service.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null);

        service.update(created.id(), "Rinominato", "", TODAY.plusDays(100));
        assertThat(service.resolve(created.id(), "HUGGINGFACE")).isEqualTo("hf_secret_abcd");
        assertThat(service.get(created.id()).name()).isEqualTo("Rinominato");
        assertThat(service.get(created.id()).expiresAt()).isEqualTo(TODAY.plusDays(100));

        service.update(created.id(), "Rinominato", "hf_new_secret_9999", null);
        assertThat(service.resolve(created.id(), "HUGGINGFACE")).isEqualTo("hf_new_secret_9999");
        assertThat(service.get(created.id()).hint()).isEqualTo("9999");

        service.create("HUGGINGFACE", "Altro", "hf_other_0000", null);
        assertRejected(() -> service.update(created.id(), "altro", "", null)); // duplicato di un ALTRO token
        assertRejected(() -> service.update(999_999L, "x", "", null));
    }

    /** Dopo il ripristino di un backup su un sistema con un'altra chiave: i token copiati non si aprono e vanno reinseriti. */
    @Test
    void undecryptableCountSeesTheTokensTheCurrentKeyCannotOpen() {
        saved("HUGGINGFACE", "Buono", "hf_secret_abcd", null);
        assertThat(service.undecryptableCount()).isZero();

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        repository.save(new Secret("CIVITAI", "Altra chiave", new org.dual.hexa.core.kernel.crypto.ChunkedAesGcmCipher(otherKey)
                .encryptBytes("cv_secret".getBytes()), "cret", null, Instant.now()));

        assertThat(service.undecryptableCount()).isEqualTo(1);
        assertThat(service.resolve(repository.findByType("HUGGINGFACE").get(0).getId(), "HUGGINGFACE")).isEqualTo("hf_secret_abcd");
    }

    @Test
    void withoutAnyKeyEveryStoredTokenIsUndecryptable() {
        saved("HUGGINGFACE", "Uno", "hf_secret_abcd", null);
        saved("CIVITAI", "Due", "cv_secret_wxyz", null);
        org.dual.hexa.core.secrets.port.in.ISecretCipher noKey = org.mockito.Mockito.mock(org.dual.hexa.core.secrets.port.in.ISecretCipher.class);
        org.mockito.Mockito.when(noKey.isConfigured()).thenReturn(false);

        var withoutKey = new SecretService(repository, noKey, events, messages, 15, Clock.systemDefaultZone(), List.of(TEST_TYPES));

        assertThat(withoutKey.undecryptableCount()).isEqualTo(2);
    }

    @Test
    void resolveRefusesAnotherProviderAnUnknownIdAndAnExpiredToken() {
        var hf = service.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null);
        var expired = saved("CIVITAI", "Vecchio", "cv_old_secret_0000", TODAY.minusDays(1));
        var lastDay = saved("CIVITAI", "Ultimo giorno", "cv_last_secret_1111", TODAY);

        assertRejected(() -> service.resolve(hf.id(), "CIVITAI"));
        assertRejected(() -> service.resolve(999_999L, "HUGGINGFACE"));
        assertRejected(() -> service.resolve(expired.getId(), "CIVITAI"));
        assertThat(service.resolve(lastDay.getId(), "CIVITAI")).isEqualTo("cv_last_secret_1111"); // vale fino a fine giornata
    }

    @Test
    void statusReflectsTheExpiryWindow() {
        saved("HUGGINGFACE", "a-senza", "hf_a_secret_0001", null);
        saved("HUGGINGFACE", "b-lontano", "hf_b_secret_0002", TODAY.plusDays(16));
        saved("HUGGINGFACE", "c-soglia", "hf_c_secret_0003", TODAY.plusDays(15));
        saved("HUGGINGFACE", "d-oggi", "hf_d_secret_0004", TODAY);
        saved("HUGGINGFACE", "e-scaduto", "hf_e_secret_0005", TODAY.minusDays(1));

        assertThat(service.list()).extracting(ISecrets.SecretView::status).containsExactly(
                ISecrets.Status.OK, ISecrets.Status.OK, ISecrets.Status.EXPIRING,
                ISecrets.Status.EXPIRING, ISecrets.Status.EXPIRED);
    }

    /** Un avviso per token scaduto o in scadenza (WARNING, source SECRETS, subject secret:<id>); niente per gli altri. */
    @Test
    void checkExpiriesWarnsOnlyForExpiredAndExpiringTokensAndGroupsRepeats() {
        saved("HUGGINGFACE", "senza", "hf_a_secret_0001", null);
        saved("HUGGINGFACE", "lontano", "hf_b_secret_0002", TODAY.plusDays(60));
        var expiring = saved("HUGGINGFACE", "in-scadenza", "hf_c_secret_0003", TODAY.plusDays(10));
        var expired = saved("CIVITAI", "scaduto", "cv_d_secret_0004", TODAY.minusDays(3));

        assertThat(service.checkExpiries()).isEqualTo(2);

        var rows = eventRepository.findAll();
        assertThat(rows).hasSize(2).allSatisfy(e -> {
            assertThat(e.getSeverity()).isEqualTo(SystemEventSeverity.WARNING);
            assertThat(e.getSource()).isEqualTo(CoreEventSource.SECRETS.name());
            assertThat(e.getAcknowledgedAt()).isNull();
        });
        assertThat(rows).extracting(SystemEvent::getSubject).containsExactlyInAnyOrder("secret:" + expiring.getId(), "secret:" + expired.getId());
        assertThat(rows).extracting(SystemEvent::getOperation).containsExactlyInAnyOrder("secretExpiring", "secretExpired");
        assertThat(rows).extracting(SystemEvent::getMessage).anySatisfy(m -> assertThat(m).contains("in-scadenza"))
                .anySatisfy(m -> assertThat(m).contains("scaduto"));
        assertThat(rows.toString()).doesNotContain("secret");

        // secondo giro nella stessa finestra: stesse serie (occurrences++), nessuna riga nuova
        service.checkExpiries();
        assertThat(eventRepository.findAll()).hasSize(2).allSatisfy(e -> assertThat(e.getOccurrences()).isEqualTo(2));
    }

    @Test
    void createWithANearExpiryWarnsImmediately() {
        service.create("HUGGINGFACE", "Presto", "hf_secret_abcd", TODAY.plusDays(3));

        assertThat(eventRepository.findAll()).hasSize(1);
        assertThat(eventRepository.findAll().get(0).getOperation()).isEqualTo("secretExpiring");
    }

    /** Rinnovare o cancellare un token toglie dalla campanella i suoi avvisi non letti. */
    @Test
    void renewingOrDeletingATokenClearsItsUnseenWarningsFromTheBell() {
        var created = service.create("HUGGINGFACE", "Presto", "hf_secret_abcd", TODAY.plusDays(3));
        var other = service.create("CIVITAI", "Altro", "cv_secret_wxyz", TODAY.plusDays(2));
        assertThat(events.unseen().count()).isEqualTo(2);

        service.update(created.id(), "Presto", "", TODAY.plusDays(200));
        assertThat(events.unseen().count()).isEqualTo(1);
        assertThat(events.unseen().latest().get(0).getSubject()).isEqualTo("secret:" + other.id());

        service.delete(other.id());
        assertThat(events.unseen().count()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void typesAreMergedFromEveryCatalogWithTheFirstDefinitionWinning() {
        SecretService withCatalog = new SecretService(repository, cipher, events, messages, 15, Clock.systemDefaultZone(),
                List.of(() -> List.of(new SecretType("HUGGINGFACE", "a"), new SecretType("CIVITAI", "a")),
                        () -> List.of(new SecretType("OAUTH2_CLIENT", "b", true), new SecretType("CIVITAI", "b"))));

        assertThat(withCatalog.types()).extracting(SecretType::name).containsExactly("HUGGINGFACE", "CIVITAI", "OAUTH2_CLIENT");
        assertThat(withCatalog.types().get(1).labelKey()).isEqualTo("a");
        assertThat(new SecretService(repository, cipher, events, messages, 15, Clock.systemDefaultZone(), List.of()).types()).isEmpty();
    }

    /** Un tipo `managed` e' dei moduli: non si crea, non si modifica e non si cancella da /secrets; i moduli usano store/deleteByNamePrefix. */
    @Test
    void aManagedTypeIsReadOnlyForTheCrudButNotForTheModulesThatOwnIt() {
        assertRejected(() -> service.create("MODULE_OWNED", "x", "valore-1234", null));
        assertRejected(() -> service.create("SCONOSCIUTO", "x", "valore-1234", null));

        var stored = service.store("MODULE_OWNED", "mod/chiave/riga", "valore-1234");
        assertThat(stored.managed()).isTrue();
        assertThat(service.resolveByName("MODULE_OWNED", "mod/chiave/riga")).contains("valore-1234");
        assertThat(service.store("MODULE_OWNED", "mod/chiave/riga", "nuovo-9999").id()).isEqualTo(stored.id());
        assertThat(service.resolveByName("MODULE_OWNED", "mod/chiave/riga")).contains("nuovo-9999");
        assertThat(service.list()).extracting(ISecrets.SecretView::managed).containsExactly(true);

        assertRejected(() -> service.update(stored.id(), "altro", "", null));
        assertRejected(() -> service.delete(stored.id()));

        service.store("MODULE_OWNED", "mod/altra", "valore-0000");
        assertThat(service.deleteByNamePrefix("MODULE_OWNED", "mod/chiave")).isEqualTo(1);
        assertThat(service.find("MODULE_OWNED", "mod/altra")).isPresent();
        assertThat(service.find("MODULE_OWNED", "mod/chiave/riga")).isEmpty();
    }

    @Test
    void withoutAnEncryptionKeyCreatingIsAConfigurationErrorAndNothingIsSaved() {
        SecretService unconfigured = new SecretService(repository, new SecretCipher("", messages), events, messages, 15, Clock.systemDefaultZone(), List.of(TEST_TYPES));

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null))
                .isInstanceOf(org.dual.hexa.core.secrets.domain.SecretException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.CONFIGURATION);
        assertThat(repository.count()).isZero();
    }
}
