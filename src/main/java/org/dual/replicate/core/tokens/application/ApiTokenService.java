package org.dual.replicate.core.tokens.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.tokens.adapter.in.scheduling.TokenExpiryScheduler;
import org.dual.replicate.core.tokens.domain.ApiToken;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.secrets.port.in.ISecretCipher;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.dual.replicate.core.tokens.port.out.IApiTokenStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * CRUD dei token API (CivitAI/HuggingFace) e loro uso nelle generazioni. Il token e' cifrato nel DB ({@link ISecretCipher}) e il
 * plaintext vive solo qui: la UI vede {@link TokenView} (nome, suffisso, scadenza, stato), le form/la chat scelgono il token
 * per ID e l'app lo risolve col plaintext con {@link #resolve} (vedi {@code TokenInputResolver}), mai nel PARAMETERS_JSON salvato.
 * Mai il segreto in log, eventi, toast o modello Thymeleaf.
 * <p>
 * Scadenza (data inserita a mano): {@link #checkExpiries} registra un AVVISO (ISystemEvents#warn, source TOKENS,
 * subject {@code token:<id>}) per i token scaduti o in scadenza entro {@code app.tokens.expiry-warning-days}; vedi
 * {@code ApiTokenExpiryService} per l'esecuzione periodica.
 */
@Service
public class ApiTokenService implements IApiTokens {

    private final IApiTokenStore repository;
    private final ISecretCipher cipher;
    private final ISystemEvents events;
    private final Messages messages;
    private final int warningDays;
    private final Clock clock;

    @Autowired
    public ApiTokenService(IApiTokenStore repository, ISecretCipher cipher, ISystemEvents events, Messages messages,
                           @Value("${app.tokens.expiry-warning-days:15}") int warningDays) {
        this(repository, cipher, events, messages, warningDays, Clock.systemDefaultZone());
    }

    ApiTokenService(IApiTokenStore repository, ISecretCipher cipher, ISystemEvents events, Messages messages,
                    int warningDays, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.events = events;
        this.messages = messages;
        this.warningDays = warningDays;
        this.clock = clock;
    }

    /** {@code false} se manca la chiave di cifratura: la pagina /tokens lo segnala e creare/modificare e' rifiutato. */
    @Override
    public boolean isConfigured() {
        return cipher.isConfigured();
    }

    @Override
    public int warningDays() {
        return warningDays;
    }

    @Override
    public List<TokenView> list() {
        return repository.findAllOrdered().stream().map(this::view).toList();
    }

    /** Token del provider, per le select delle form di generazione (nome + scadenza, mai il segreto). */
    @Override
    public List<TokenView> options(String provider) {
        return repository.findByProvider(provider).stream().map(this::view).toList();
    }

    @Override
    public TokenView get(Long id) {
        return view(find(id));
    }

    @Override
    public TokenView create(String provider, String name, String token, LocalDate expiresAt) {
        String cleanName = validName(name);
        if (provider == null || provider.isBlank()) {
            throw new TokenException(messages.get("tokens.error.providerRequired"));
        }
        if (repository.existsByProviderAndName(provider, cleanName)) {
            throw new TokenException(messages.get("tokens.error.nameDuplicate", cleanName));
        }
        String cleanToken = validToken(token, true);
        if (expiresAt != null && expiresAt.isBefore(today())) {
            throw new TokenException(messages.get("tokens.error.expiryInPast"));
        }
        requireCipher();
        Instant now = clock.instant();
        ApiToken saved = repository.save(new ApiToken(provider, cleanName, cipher.encrypt(cleanToken), hint(cleanToken), expiresAt, now));
        checkExpiry(saved);
        return view(saved);
    }

    /** {@code token} vuoto/null = lascia il token com'e'. Rinnovare la scadenza toglie dalla campanella gli avvisi di questo token. */
    @Override
    public TokenView update(Long id, String name, String token, LocalDate expiresAt) {
        ApiToken existing = find(id);
        String cleanName = validName(name);
        if (repository.existsByProviderAndNameExcluding(existing.getProvider(), cleanName, id)) {
            throw new TokenException(messages.get("tokens.error.nameDuplicate", cleanName));
        }
        String cleanToken = validToken(token, false);
        requireCipher();
        Instant now = clock.instant();
        existing.update(cleanName, expiresAt, now);
        if (cleanToken != null) {
            existing.replaceToken(cipher.encrypt(cleanToken), hint(cleanToken), now);
        }
        ApiToken saved = repository.save(existing);
        events.markSeenBySubject(subject(saved));
        checkExpiry(saved);
        return view(saved);
    }

    @Override
    public void delete(Long id) {
        ApiToken existing = find(id);
        repository.delete(existing);
        events.markSeenBySubject(subject(existing));
    }

    /** Plaintext del token scelto, per Replicate. Inesistente o scaduto: rifiuto atteso (nessuna prediction a pagamento parte). */
    @Override
    public String resolve(Long id, String provider) {
        ApiToken token = repository.findById(id)
                .filter(t -> t.getProvider().equals(provider))
                .orElseThrow(() -> new TokenException(messages.get("tokens.error.missing", provider)));
        if (isExpired(token)) {
            throw new TokenException(messages.get("tokens.error.expired", token.getName()));
        }
        return cipher.decrypt(token.getTokenEncrypted());
    }

    /** Controllo di scadenza di tutti i token (job periodico, avvio): un avviso per ogni token scaduto o in scadenza. */
    @Override
    public int checkExpiries() {
        int warned = 0;
        for (ApiToken token : repository.findAll()) {
            if (checkExpiry(token)) {
                warned++;
            }
        }
        return warned;
    }

    /** Registra l'avviso di scadenza del token, se serve. @return {@code true} se ne ha registrato uno. */
    boolean checkExpiry(ApiToken token) {
        if (token.getExpiresAt() == null) {
            return false;
        }
        LocalDate today = today();
        String provider = messages.get("tokens.provider." + token.getProvider());
        if (today.isAfter(token.getExpiresAt())) {
            events.warn(CoreEventSource.TOKENS, "tokenExpired", subject(token),
                    messages.get("tokens.warning.expired", token.getName(), provider, token.getExpiresAt().toString()));
            return true;
        }
        long days = ChronoUnit.DAYS.between(today, token.getExpiresAt());
        if (days <= warningDays) {
            events.warn(CoreEventSource.TOKENS, "tokenExpiring", subject(token),
                    messages.get("tokens.warning.expiring", token.getName(), provider, token.getExpiresAt().toString(), days));
            return true;
        }
        return false;
    }

    static String subject(ApiToken token) {
        return "token:" + token.getId();
    }

    private TokenView view(ApiToken token) {
        Status status = isExpired(token) ? Status.EXPIRED
                : token.getExpiresAt() != null && ChronoUnit.DAYS.between(today(), token.getExpiresAt()) <= warningDays ? Status.EXPIRING
                : Status.OK;
        return new TokenView(token.getId(), token.getProvider(), token.getName(), token.getTokenHint(), token.getExpiresAt(), status);
    }

    private boolean isExpired(ApiToken token) {
        return token.getExpiresAt() != null && today().isAfter(token.getExpiresAt());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private ApiToken find(Long id) {
        return repository.findById(id).orElseThrow(() -> new TokenException(messages.get("tokens.error.notFound")));
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
            throw new TokenException(messages.get("tokens.error.nameRequired"));
        }
        if (clean.length() > MAX_NAME) {
            throw new TokenException(messages.get("tokens.error.nameTooLong", MAX_NAME));
        }
        return clean;
    }

    /** {@code required}=false: vuoto = null (lascia invariato). */
    private String validToken(String token, boolean required) {
        String clean = token == null ? "" : token.strip();
        if (clean.isEmpty()) {
            if (required) {
                throw new TokenException(messages.get("tokens.error.tokenRequired"));
            }
            return null;
        }
        if (clean.length() > MAX_TOKEN) {
            throw new TokenException(messages.get("tokens.error.tokenTooLong", MAX_TOKEN));
        }
        return clean;
    }

    private static String hint(String token) {
        return token.length() <= 4 ? token : token.substring(token.length() - 4);
    }
}
