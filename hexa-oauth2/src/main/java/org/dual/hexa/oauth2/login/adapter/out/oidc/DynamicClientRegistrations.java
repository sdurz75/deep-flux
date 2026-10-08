package org.dual.hexa.oauth2.login.adapter.out.oidc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.hexa.core.kernel.remote.RestRemoteClient;
import org.dual.hexa.core.kernel.remote.RetryPolicy;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.core.config.domain.ModuleConfigChangedEvent;
import org.dual.hexa.oauth2.login.domain.ConfigKeys;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Il repository dei client OIDC di Spring Security, ma DINAMICO: un provider si aggiunge dalla UI (o dalle variabili d'ambiente) senza riavviare. La
 * registrazione si costruisce dalla discovery ({@code <issuer>/.well-known/openid-configuration}, letta con il {@code RestClient.Builder} iniettato, via
 * {@code RemoteCaller}) e si tiene in cache qualche minuto; il segreto del client (campo {@code SECRET} delle impostazioni o variabile d'ambiente) vive in chiaro solo qui
 * dentro. Un guasto si registra come evento e il provider risulta assente (il login mostra un errore).
 */
@Component
class DynamicClientRegistrations extends RestRemoteClient implements ClientRegistrationRepository {

    static final Duration TTL = Duration.ofMinutes(10);
    static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private record Cached(ClientRegistration registration, Instant expiresAt) {
    }

    private final RestClient restClient;
    private final IOAuthProviders providers;
    private final ISystemEvents events;
    private final Messages messages;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    DynamicClientRegistrations(RestClient.Builder restClientBuilder, IOAuthProviders providers,
                               ISystemEvents events, Messages messages) {
        super("oauth2.remote", messages, OAuthException::new, RetryPolicy.DEFAULT);
        this.restClient = restClientBuilder.build();
        this.providers = providers;
        this.events = events;
        this.messages = messages;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        Cached cached = cache.get(registrationId);
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
            return cached.registration();
        }
        Optional<ProviderConfig> config = providers.find(registrationId);
        if (config.isEmpty()) {
            cache.remove(registrationId);
            return null;
        }
        try {
            ClientRegistration registration = build(config.get());
            cache.put(registrationId, new Cached(registration, clock.instant().plus(TTL)));
            return registration;
        } catch (RuntimeException e) {
            // Chi gestisce l'errore lo registra: il login non puo' proseguire, ma l'amministratore lo trova fra gli eventi.
            events.record("oidcDiscovery", e, "oauth2:" + registrationId);
            return null;
        }
    }

    /** Un provider o il suo segreto sono cambiati: la discovery si rifa' subito (un segreto ruotato vale ora, non fra dieci minuti). */
    @EventListener
    void settingsChanged(ModuleConfigChangedEvent event) {
        if (ConfigKeys.MODULE.equals(event.moduleId())) {
            cache.clear();
        }
    }

    private ClientRegistration build(ProviderConfig config) {
        String secret = providers.clientSecret(config.slug()).orElseThrow(() -> new OAuthException(messages.get("oauth2.error.secretRequired")));
        Map<String, Object> metadata = remote.call("discovery", () -> {
            Map<String, Object> body = restClient.get().uri(config.issuerUri() + "/.well-known/openid-configuration").retrieve().body(JSON);
            if (body == null) {
                throw new OAuthException(messages.get("oauth2.remote.error.metadata", config.issuerUri()), null, Kind.PERMANENT);
            }
            return body;
        });
        // OIDC Discovery 4.3: l'issuer dichiarato deve coincidere con quello da cui si e' letta la configurazione.
        String declared = text(metadata, "issuer");
        if (declared == null || !declared.replaceAll("/+$", "").equals(config.issuerUri())) {
            throw new OAuthException(messages.get("oauth2.remote.error.issuerMismatch", config.issuerUri(), declared), null, Kind.PERMANENT);
        }
        String authorization = require(metadata, "authorization_endpoint", config);
        String token = require(metadata, "token_endpoint", config);
        String jwks = require(metadata, "jwks_uri", config);
        return ClientRegistration.withRegistrationId(config.slug())
                .clientName(config.title())
                .clientId(config.clientId())
                .clientSecret(secret)
                .clientAuthenticationMethod(authenticationMethod(metadata))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI)
                .scope("openid", "email", "profile")
                .authorizationUri(authorization)
                .tokenUri(token)
                .jwkSetUri(jwks)
                .issuerUri(config.issuerUri())
                .userInfoUri(text(metadata, "userinfo_endpoint"))
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .build();
    }

    private String require(Map<String, Object> metadata, String key, ProviderConfig config) {
        String value = text(metadata, key);
        if (value == null) {
            throw new OAuthException(messages.get("oauth2.remote.error.metadataKey", key, config.issuerUri()), null, Kind.PERMANENT);
        }
        return value;
    }

    private static String text(Map<String, Object> metadata, String key) {
        return metadata.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    /** Basic di default; Post solo se il provider dichiara di non supportare Basic. */
    private static ClientAuthenticationMethod authenticationMethod(Map<String, Object> metadata) {
        if (metadata.get("token_endpoint_auth_methods_supported") instanceof List<?> methods
                && !methods.contains("client_secret_basic") && methods.contains("client_secret_post")) {
            return ClientAuthenticationMethod.CLIENT_SECRET_POST;
        }
        return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
    }
}
