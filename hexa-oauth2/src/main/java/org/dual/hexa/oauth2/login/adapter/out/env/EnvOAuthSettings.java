package org.dual.hexa.oauth2.login.adapter.out.env;

import java.util.Optional;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Le variabili {@code HX_OAUTH2_*} che non sono campi di configurazione (o, a parita' di nome, le property {@code app.oauth2.*}, che vincono). Nessun file da
 * importare: i default stanno nei placeholder, come per le altre chiavi dei moduli hexa-* con un default nel codice.
 *
 * <ul>
 *   <li>{@code HX_OAUTH2_ENABLED}: cancello acceso (default {@code false}), autorevole; {@code HX_OAUTH2_RESET}: spento finche' impostata.
 *   <li>{@code HX_OAUTH2_PROVIDER} ({@code google} oppure vuoto/{@code custom}), {@code HX_OAUTH2_ISSUER_URI} (non serve per google),
 *       {@code HX_OAUTH2_CLIENT_ID}, {@code HX_OAUTH2_CLIENT_SECRET}, {@code HX_OAUTH2_TITLE}: il provider d'ambiente.
 * </ul>
 * Gli elenchi {@code HX_OAUTH2_ALLOWED_EMAILS} e {@code HX_OAUTH2_ALLOWED_DOMAINS} sono campi del modulo di configurazione (alias d'ambiente, additivi).
 */
@Component
class EnvOAuthSettings implements IOAuthEnvironment {

    static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private final boolean enabled;
    private final boolean reset;
    private final Optional<EnvProvider> provider;

    EnvOAuthSettings(@Value("${app.oauth2.enabled:${HX_OAUTH2_ENABLED:false}}") boolean enabled,
                     @Value("${app.oauth2.reset:${HX_OAUTH2_RESET:false}}") boolean reset,
                     @Value("${app.oauth2.provider:${HX_OAUTH2_PROVIDER:}}") String preset,
                     @Value("${app.oauth2.title:${HX_OAUTH2_TITLE:}}") String title,
                     @Value("${app.oauth2.issuer-uri:${HX_OAUTH2_ISSUER_URI:}}") String issuerUri,
                     @Value("${app.oauth2.client-id:${HX_OAUTH2_CLIENT_ID:}}") String clientId,
                     @Value("${app.oauth2.client-secret:${HX_OAUTH2_CLIENT_SECRET:}}") String clientSecret) {
        this.enabled = enabled;
        this.reset = reset;
        this.provider = provider(preset, title, issuerUri, clientId, clientSecret);
    }

    private static Optional<EnvProvider> provider(String preset, String title, String issuerUri, String clientId, String clientSecret) {
        boolean google = "google".equalsIgnoreCase(preset == null ? "" : preset.strip());
        String issuer = issuerUri == null ? "" : issuerUri.strip().replaceAll("/+$", "");
        if (issuer.isEmpty() && google) {
            issuer = GOOGLE_ISSUER;
        }
        String id = clientId == null ? "" : clientId.strip();
        String secret = clientSecret == null ? "" : clientSecret.strip();
        if (issuer.isEmpty() || id.isEmpty() || secret.isEmpty()) {
            return Optional.empty();
        }
        String label = title == null || title.isBlank() ? (google ? "Google" : "Single sign-on") : title.strip();
        return Optional.of(new EnvProvider(google ? "google" : "sso", label, issuer, id, secret));
    }

    @Override
    public boolean gateEnabled() {
        return enabled;
    }

    @Override
    public boolean reset() {
        return reset;
    }

    @Override
    public Optional<EnvProvider> provider() {
        return provider;
    }
}
