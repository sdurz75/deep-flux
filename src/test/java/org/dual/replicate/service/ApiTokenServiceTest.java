package org.dual.replicate.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.domain.ApiToken;
import org.dual.replicate.domain.SystemEvent;
import org.dual.replicate.domain.SystemEventSeverity;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.remote.RemoteServiceException;
import org.dual.replicate.repository.ApiTokenRepository;
import org.dual.replicate.repository.SystemEventRepository;
import org.dual.replicate.service.secret.SecretCipher;
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
class ApiTokenServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Autowired
    private ApiTokenRepository repository;
    @Autowired
    private SystemEventRepository eventRepository;
    @Autowired
    private SecretCipher cipher;
    @Autowired
    private SystemEventService events;
    @Autowired
    private Messages messages;

    private ApiTokenService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        eventRepository.deleteAll();
        service = new ApiTokenService(repository, cipher, events, messages, 15,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    private ApiToken saved(String provider, String name, String plain, LocalDate expires) {
        return repository.save(new ApiToken(provider, name, cipher.encrypt(plain), plain.substring(plain.length() - 4), expires, Instant.now()));
    }

    private void assertRejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(TokenException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.REJECTED);
    }

    @Test
    void createStoresTheTokenEncryptedWithOnlyAHintInTheClear() {
        var view = service.create("HUGGINGFACE", "  Personale ", " hf_secret_abcd ", null);

        assertThat(view.name()).isEqualTo("Personale");
        assertThat(view.hint()).isEqualTo("abcd");
        ApiToken row = repository.findById(view.id()).orElseThrow();
        assertThat(new String(row.getTokenEncrypted(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("hf_secret");
        assertThat(row.toString()).doesNotContain("hf_secret").doesNotContain("abcd");
        assertThat(service.resolve(view.id(), "HUGGINGFACE")).isEqualTo("hf_secret_abcd");
    }

    @Test
    void validationRejectsBlankDuplicateAndOversizedInputsButAllowsTheSameNameOnAnotherProvider() {
        service.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null);

        assertRejected(() -> service.create("HUGGINGFACE", "  ", "x1234", null));
        assertRejected(() -> service.create("HUGGINGFACE", "x".repeat(ApiTokenService.MAX_NAME + 1), "x1234", null));
        assertRejected(() -> service.create("HUGGINGFACE", "personale", "x1234", null)); // maiuscole/minuscole
        assertRejected(() -> service.create("HUGGINGFACE", "Altro", "", null));
        assertRejected(() -> service.create("HUGGINGFACE", "Altro", "t".repeat(ApiTokenService.MAX_TOKEN + 1), null));
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

        assertThat(service.list()).extracting(ApiTokenService.TokenView::status).containsExactly(
                ApiTokenService.Status.OK, ApiTokenService.Status.OK, ApiTokenService.Status.EXPIRING,
                ApiTokenService.Status.EXPIRING, ApiTokenService.Status.EXPIRED);
    }

    /** Un avviso per token scaduto o in scadenza (WARNING, source TOKENS, subject token:<id>); niente per gli altri. */
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
            assertThat(e.getSource()).isEqualTo(CoreEventSource.TOKENS.name());
            assertThat(e.getAcknowledgedAt()).isNull();
        });
        assertThat(rows).extracting(SystemEvent::getSubject).containsExactlyInAnyOrder("token:" + expiring.getId(), "token:" + expired.getId());
        assertThat(rows).extracting(SystemEvent::getOperation).containsExactlyInAnyOrder("tokenExpiring", "tokenExpired");
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
        assertThat(eventRepository.findAll().get(0).getOperation()).isEqualTo("tokenExpiring");
    }

    /** Rinnovare o cancellare un token toglie dalla campanella i suoi avvisi non letti. */
    @Test
    void renewingOrDeletingATokenClearsItsUnseenWarningsFromTheBell() {
        var created = service.create("HUGGINGFACE", "Presto", "hf_secret_abcd", TODAY.plusDays(3));
        var other = service.create("CIVITAI", "Altro", "cv_secret_wxyz", TODAY.plusDays(2));
        assertThat(events.unseen().count()).isEqualTo(2);

        service.update(created.id(), "Presto", "", TODAY.plusDays(200));
        assertThat(events.unseen().count()).isEqualTo(1);
        assertThat(events.unseen().latest().get(0).getSubject()).isEqualTo("token:" + other.id());

        service.delete(other.id());
        assertThat(events.unseen().count()).isZero();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void withoutAnEncryptionKeyCreatingIsAConfigurationErrorAndNothingIsSaved() {
        ApiTokenService unconfigured = new ApiTokenService(repository, new SecretCipher("", messages), events, messages, 15, Clock.systemDefaultZone());

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.create("HUGGINGFACE", "Personale", "hf_secret_abcd", null))
                .isInstanceOf(org.dual.replicate.service.secret.SecretException.class)
                .extracting(e -> ((RemoteServiceException) e).kind()).isEqualTo(RemoteServiceException.Kind.CONFIGURATION);
        assertThat(repository.count()).isZero();
    }
}
