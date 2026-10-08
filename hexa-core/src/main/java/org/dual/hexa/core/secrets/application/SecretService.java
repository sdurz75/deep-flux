package org.dual.hexa.core.secrets.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.secrets.domain.Secret;
import org.dual.hexa.core.secrets.domain.SecretException;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.in.ISecretCipher;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.secrets.port.out.ISecretStore;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * CRUD dei segreti (token API di un servizio, password, segreti di client OAuth2...) e loro uso. Il valore e' cifrato nel DB ({@link ISecretCipher}) e il
 * plaintext vive solo qui: la UI vede {@link SecretView} (nome, suffisso, scadenza, stato), le form/la chat scelgono il segreto per ID e l'app lo risolve
 * col plaintext con {@link #resolve} (vedi {@code SecretInputResolver}), mai nel PARAMETERS_JSON salvato. Mai il valore in log, eventi, toast o modello.
 * <p>
 * I TIPI vengono dai bean {@link ISecretTypeCatalog} (core, app, moduli: si sommano). Un tipo {@code managed} e' dei moduli: {@code create}/{@code update}/
 * {@code delete} lo rifiutano, e i moduli usano {@link #store}/{@link #deleteByNamePrefix}.
 * <p>
 * Scadenza (data inserita a mano): {@link #checkExpiries} registra un AVVISO (ISystemEvents#warn, source SECRETS, subject {@code secret:<id>}) per i segreti
 * scaduti o in scadenza entro {@code app.secrets.expiry-warning-days}; vedi {@code SecretExpiryScheduler} per l'esecuzione periodica.
 */
@Service
public class SecretService implements ISecrets {

    private final ISecretStore repository;
    private final ISecretCipher cipher;
    private final ISystemEvents events;
    private final Messages messages;
    private final int warningDays;
    private final Clock clock;
    private final List<ISecretTypeCatalog> catalogs;

    @Autowired
    public SecretService(ISecretStore repository, ISecretCipher cipher, ISystemEvents events, Messages messages,
                         @Value("${app.secrets.expiry-warning-days:15}") int warningDays,
                         ObjectProvider<ISecretTypeCatalog> catalogs) {
        this(repository, cipher, events, messages, warningDays, Clock.systemDefaultZone(), catalogs.orderedStream().toList());
    }

    SecretService(ISecretStore repository, ISecretCipher cipher, ISystemEvents events, Messages messages,
                  int warningDays, Clock clock, List<ISecretTypeCatalog> catalogs) {
        this.repository = repository;
        this.cipher = cipher;
        this.events = events;
        this.messages = messages;
        this.warningDays = warningDays;
        this.clock = clock;
        this.catalogs = catalogs;
    }

    /** {@code false} se manca la chiave di cifratura: la pagina /secrets lo segnala e creare/modificare e' rifiutato. */
    @Override
    public boolean isConfigured() {
        return cipher.isConfigured();
    }

    @Override
    public int warningDays() {
        return warningDays;
    }

    @Override
    public List<SecretType> types() {
        Map<String, SecretType> byName = new LinkedHashMap<>();
        catalogs.forEach(catalog -> catalog.types().forEach(type -> byName.putIfAbsent(type.name(), type)));
        return List.copyOf(byName.values());
    }

    private Optional<SecretType> type(String name) {
        return types().stream().filter(type -> type.name().equals(name)).findFirst();
    }

    @Override
    public List<SecretView> list() {
        return repository.findAllOrdered().stream().map(this::view).toList();
    }

    /** Segreti del tipo, per le select delle form di generazione (nome + scadenza, mai il valore). */
    @Override
    public List<SecretView> options(String type) {
        return repository.findByType(type).stream().map(this::view).toList();
    }

    @Override
    public SecretView get(Long id) {
        return view(find(id));
    }

    @Override
    public SecretView create(String type, String name, String value, LocalDate expiresAt) {
        String cleanName = validName(name);
        if (type == null || type.isBlank()) {
            throw new SecretException(messages.get("secrets.error.typeRequired"));
        }
        SecretType known = type(type).orElseThrow(() -> new SecretException(messages.get("secrets.error.typeRequired")));
        if (known.managed()) {
            throw new SecretException(messages.get("secrets.error.managed"));
        }
        if (repository.existsByTypeAndName(type, cleanName)) {
            throw new SecretException(messages.get("secrets.error.nameDuplicate", cleanName));
        }
        String cleanValue = validValue(value, true);
        if (expiresAt != null && expiresAt.isBefore(today())) {
            throw new SecretException(messages.get("secrets.error.expiryInPast"));
        }
        requireCipher();
        Instant now = clock.instant();
        Secret saved = repository.save(new Secret(type, cleanName, cipher.encrypt(cleanValue), hint(cleanValue), expiresAt, now));
        checkExpiry(saved);
        return view(saved);
    }

    /** {@code value} vuoto/null = lascia il valore com'e'. Rinnovare la scadenza toglie dalla campanella gli avvisi di questo segreto. */
    @Override
    public SecretView update(Long id, String name, String value, LocalDate expiresAt) {
        Secret existing = find(id);
        if (isManaged(existing)) {
            throw new SecretException(messages.get("secrets.error.managed"));
        }
        String cleanName = validName(name);
        if (repository.existsByTypeAndNameExcluding(existing.getType(), cleanName, id)) {
            throw new SecretException(messages.get("secrets.error.nameDuplicate", cleanName));
        }
        String cleanValue = validValue(value, false);
        requireCipher();
        Instant now = clock.instant();
        existing.update(cleanName, expiresAt, now);
        if (cleanValue != null) {
            existing.replaceValue(cipher.encrypt(cleanValue), hint(cleanValue), now);
        }
        Secret saved = repository.save(existing);
        events.markSeenBySubject(subject(saved));
        checkExpiry(saved);
        return view(saved);
    }

    @Override
    public void delete(Long id) {
        Secret existing = find(id);
        if (isManaged(existing)) {
            throw new SecretException(messages.get("secrets.error.managed"));
        }
        repository.delete(existing);
        events.markSeenBySubject(subject(existing));
    }

    /** Valore in chiaro del segreto scelto. Inesistente o scaduto: rifiuto atteso (nessuna prediction a pagamento parte). */
    @Override
    public String resolve(Long id, String type) {
        Secret secret = repository.findById(id)
                .filter(s -> s.getType().equals(type))
                .orElseThrow(() -> new SecretException(messages.get("secrets.error.missing", label(type))));
        if (isExpired(secret)) {
            throw new SecretException(messages.get("secrets.error.expired", secret.getName()));
        }
        return cipher.decrypt(secret.getValueEncrypted());
    }

    @Override
    public SecretView store(String type, String name, String value) {
        String cleanName = validName(name);
        String cleanValue = validValue(value, true);
        requireCipher();
        Instant now = clock.instant();
        Secret secret = repository.findByTypeAndName(type, cleanName).orElse(null);
        if (secret == null) {
            secret = new Secret(type, cleanName, cipher.encrypt(cleanValue), hint(cleanValue), null, now);
        } else {
            secret.replaceValue(cipher.encrypt(cleanValue), hint(cleanValue), now);
        }
        return view(repository.save(secret));
    }

    @Override
    public Optional<SecretView> find(String type, String name) {
        return repository.findByTypeAndName(type, name).map(this::view);
    }

    @Override
    public Optional<String> resolveByName(String type, String name) {
        return repository.findByTypeAndName(type, name).map(secret -> cipher.decrypt(secret.getValueEncrypted()));
    }

    @Override
    public boolean deleteByName(String type, String name) {
        return repository.findByTypeAndName(type, name).map(secret -> {
            repository.delete(secret);
            events.markSeenBySubject(subject(secret));
            return true;
        }).orElse(false);
    }

    @Override
    public int deleteByNamePrefix(String type, String prefix) {
        List<Secret> found = repository.findByTypeAndNamePrefix(type, prefix);
        found.forEach(secret -> {
            repository.delete(secret);
            events.markSeenBySubject(subject(secret));
        });
        return found.size();
    }

    @Override
    public int undecryptableCount() {
        List<Secret> all = repository.findAll();
        if (!cipher.isConfigured()) {
            return all.size();
        }
        int broken = 0;
        for (Secret secret : all) {
            try {
                cipher.decrypt(secret.getValueEncrypted());
            } catch (RuntimeException e) {
                broken++;
            }
        }
        return broken;
    }

    /** Controllo di scadenza di tutti i segreti (job periodico, avvio): un avviso per ogni segreto scaduto o in scadenza. */
    @Override
    public int checkExpiries() {
        int warned = 0;
        for (Secret secret : repository.findAll()) {
            if (checkExpiry(secret)) {
                warned++;
            }
        }
        return warned;
    }

    /** Registra l'avviso di scadenza del segreto, se serve. @return {@code true} se ne ha registrato uno. */
    boolean checkExpiry(Secret secret) {
        if (secret.getExpiresAt() == null) {
            return false;
        }
        LocalDate today = today();
        String type = label(secret.getType());
        if (today.isAfter(secret.getExpiresAt())) {
            events.warn(CoreEventSource.SECRETS, "secretExpired", subject(secret),
                    messages.get("secrets.warning.expired", secret.getName(), type, secret.getExpiresAt().toString()));
            return true;
        }
        long days = ChronoUnit.DAYS.between(today, secret.getExpiresAt());
        if (days <= warningDays) {
            events.warn(CoreEventSource.SECRETS, "secretExpiring", subject(secret),
                    messages.get("secrets.warning.expiring", secret.getName(), type, secret.getExpiresAt().toString(), days));
            return true;
        }
        return false;
    }

    static String subject(Secret secret) {
        return "secret:" + secret.getId();
    }

    /** L'etichetta del tipo (dal bundle di chi lo ha registrato), o il suo nome se non c'e'. */
    private String label(String typeName) {
        return type(typeName).map(type -> messages.getOrDefault(type.labelKey(), type.name())).orElse(typeName);
    }

    private boolean isManaged(Secret secret) {
        return type(secret.getType()).map(SecretType::managed).orElse(false);
    }

    private SecretView view(Secret secret) {
        Status status = isExpired(secret) ? Status.EXPIRED
                : secret.getExpiresAt() != null && ChronoUnit.DAYS.between(today(), secret.getExpiresAt()) <= warningDays ? Status.EXPIRING
                : Status.OK;
        return new SecretView(secret.getId(), secret.getType(), secret.getName(), secret.getHint(), secret.getExpiresAt(), status, isManaged(secret));
    }

    private boolean isExpired(Secret secret) {
        return secret.getExpiresAt() != null && today().isAfter(secret.getExpiresAt());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Secret find(Long id) {
        return repository.findById(id).orElseThrow(() -> new SecretException(messages.get("secrets.error.notFound")));
    }

    private void requireCipher() {
        if (!cipher.isConfigured()) {
            // Stesso errore di SecretCipher (CONFIGURATION), sollevato PRIMA di toccare il DB.
            cipher.encrypt("");
        }
    }

    private String validName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new SecretException(messages.get("secrets.error.nameRequired"));
        }
        if (clean.length() > MAX_NAME) {
            throw new SecretException(messages.get("secrets.error.nameTooLong", MAX_NAME));
        }
        return clean;
    }

    /** {@code required}=false: vuoto = null (lascia invariato). */
    private String validValue(String value, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            if (required) {
                throw new SecretException(messages.get("secrets.error.valueRequired"));
            }
            return null;
        }
        if (clean.length() > MAX_VALUE) {
            throw new SecretException(messages.get("secrets.error.valueTooLong", MAX_VALUE));
        }
        return clean;
    }

    private static String hint(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
