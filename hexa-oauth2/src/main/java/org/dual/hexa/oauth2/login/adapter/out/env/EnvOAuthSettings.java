package org.dual.hexa.oauth2.login.adapter.out.env;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Le variabili {@code HX_OAUTH2_*} (o, a parita' di nome, le property {@code app.oauth2.*}, che vincono). Nessun file da importare: i default stanno nei
 * placeholder, come per le altre chiavi dei moduli hexa-* con un default nel codice.
 *
 * <ul>
 *   <li>{@code HX_OAUTH2_ENABLED}: cancello acceso (default {@code false}); {@code HX_OAUTH2_RESET}: spento finche' impostata.
 *   <li>{@code HX_OAUTH2_PROVIDER} ({@code google} oppure vuoto/{@code custom}), {@code HX_OAUTH2_ISSUER_URI} (non serve per google),
 *       {@code HX_OAUTH2_CLIENT_ID}, {@code HX_OAUTH2_CLIENT_SECRET}, {@code HX_OAUTH2_TITLE}: il provider d'ambiente.
 *   <li>{@code HX_OAUTH2_ALLOWED_EMAILS}, {@code HX_OAUTH2_ALLOWED_DOMAINS}: elenchi separati da virgole, aggiunti a quelli salvati dalla UI.
 * </ul>
 */
@Component
class EnvOAuthSettings implements IOAuthEnvironment {

    static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private final boolean enabled;
    private final boolean reset;
    private final Optional<EnvProvider> provider;
    private final List<String> emails;
    private final List<String> domains;

    EnvOAuthSettings(@Value("${app.oauth2.enabled:${HX_OAUTH2_ENABLED:false}}") boolean enabled,
                     @Value("${app.oauth2.reset:${HX_OAUTH2_RESET:false}}") boolean reset,
                     @Value("${app.oauth2.provider:${HX_OAUTH2_PROVIDER:}}") String preset,
                     @Value("${app.oauth2.title:${HX_OAUTH2_TITLE:}}") String title,
                     @Value("${app.oauth2.issuer-uri:${HX_OAUTH2_ISSUER_URI:}}") String issuerUri,
                     @Value("${app.oauth2.client-id:${HX_OAUTH2_CLIENT_ID:}}") String clientId,
                     @Value("${app.oauth2.client-secret:${HX_OAUTH2_CLIENT_SECRET:}}") String clientSecret,
                     @Value("${app.oauth2.allowed-emails:${HX_OAUTH2_ALLOWED_EMAILS:}}") String allowedEmails,
                     @Value("${app.oauth2.allowed-domains:${HX_OAUTH2_ALLOWED_DOMAINS:}}") String allowedDomains) {
        this.enabled = enabled;
        this.reset = reset;
        this.provider = provider(preset, title, issuerUri, clientId, clientSecret);
        this.emails = list(allowedEmails, false);
        this.domains = list(allowedDomains, true);
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

    private static List<String> list(String csv, boolean domains) {
        if (csv == null) {
            return List.of();
        }
        return Arrays.stream(csv.split("[,;\\s]+")).map(String::strip).map(value -> value.toLowerCase(Locale.ROOT))
                .map(value -> domains && value.startsWith("@") ? value.substring(1) : value).filter(value -> !value.isEmpty()).distinct().toList();
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

    @Override
    public List<String> allowedEmails() {
        return emails;
    }

    @Override
    public List<String> allowedDomains() {
        return domains;
    }
}
