package org.dual.hexa.oauth2.login.application;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.domain.OAuthProvider;
import org.dual.hexa.oauth2.login.domain.ProviderChanged;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.dual.hexa.oauth2.login.port.out.IClientSecrets;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.dual.hexa.oauth2.login.port.out.IProviderStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProviderService implements IOAuthProviders {

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,29}");
    private static final int MAX_TITLE = 60;
    private static final int MAX_URL = 300;

    private final IProviderStore store;
    private final IOAuthEnvironment env;
    private final IClientSecrets secrets;
    private final IOAuthAccess access;
    private final Messages messages;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Autowired
    ProviderService(IProviderStore store, IOAuthEnvironment env, IClientSecrets secrets, IOAuthAccess access, Messages messages,
                    ApplicationEventPublisher publisher) {
        this(store, env, secrets, access, messages, publisher, Clock.systemUTC());
    }

    ProviderService(IProviderStore store, IOAuthEnvironment env, IClientSecrets secrets, IOAuthAccess access, Messages messages,
                    ApplicationEventPublisher publisher, Clock clock) {
        this.store = store;
        this.env = env;
        this.secrets = secrets;
        this.access = access;
        this.messages = messages;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProviderConfig> list() {
        List<ProviderConfig> all = new ArrayList<>();
        env.provider().ifPresent(p -> all.add(new ProviderConfig(null, p.slug(), p.title(), p.issuerUri(), p.clientId(), null, true)));
        store.findAll().forEach(p -> all.add(p.toConfig()));
        return all;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProviderConfig> find(String slug) {
        return list().stream().filter(p -> p.slug().equals(slug)).findFirst();
    }

    @Override
    @Transactional
    public ProviderConfig create(String slug, String title, String issuerUri, String clientId, Long secretTokenId) {
        String cleanSlug = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(cleanSlug).matches()) {
            throw new OAuthException(messages.get("oauth2.error.slugFormat"));
        }
        if (env.provider().map(p -> p.slug().equals(cleanSlug)).orElse(false) || store.findBySlug(cleanSlug).isPresent()) {
            throw new OAuthException(messages.get("oauth2.error.slugTaken", cleanSlug));
        }
        String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty() || cleanTitle.length() > MAX_TITLE) {
            throw new OAuthException(messages.get("oauth2.error.titleRequired", MAX_TITLE));
        }
        String cleanIssuer = issuerUri == null ? "" : issuerUri.strip().replaceAll("/+$", "");
        if (!validIssuer(cleanIssuer)) {
            throw new OAuthException(messages.get("oauth2.error.issuerFormat"));
        }
        String cleanClient = clientId == null ? "" : clientId.strip();
        if (cleanClient.isEmpty() || cleanClient.length() > MAX_URL) {
            throw new OAuthException(messages.get("oauth2.error.clientIdRequired"));
        }
        if (secretTokenId == null || !secrets.exists(secretTokenId)) {
            throw new OAuthException(messages.get("oauth2.error.secretRequired"));
        }
        OAuthProvider saved = store.save(new OAuthProvider(cleanSlug, cleanTitle, cleanIssuer, cleanClient, secretTokenId, clock.instant()));
        publisher.publishEvent(new ProviderChanged());
        return saved.toConfig();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        OAuthProvider provider = store.findById(id).orElseThrow(() -> new OAuthException(messages.get("oauth2.error.notFound")));
        long others = store.count() - 1 + (env.provider().isPresent() ? 1 : 0);
        if (access.isEnabled() && others <= 0) {
            throw new OAuthException(messages.get("oauth2.error.lastProvider"));
        }
        store.delete(provider);
        publisher.publishEvent(new ProviderChanged());
    }

    /** https obbligatorio; http solo verso localhost (provider di prova). */
    private static boolean validIssuer(String issuer) {
        if (issuer.isEmpty() || issuer.length() > MAX_URL) {
            return false;
        }
        try {
            URI uri = URI.create(issuer);
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return false;
            }
            return "https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
