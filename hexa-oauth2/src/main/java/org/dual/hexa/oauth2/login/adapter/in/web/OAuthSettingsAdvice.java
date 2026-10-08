package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.dual.hexa.oauth2.login.domain.GateStatus;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * I dati del fragment {@code oauth2-settings :: extra} (senza parametri: arrivano dal model): stato del cancello e provider con l'indirizzo di ritorno da
 * registrare. Solo per le richieste di {@code /settings}: le altre pagine non pagano nulla.
 */
@ControllerAdvice
class OAuthSettingsAdvice {

    /** Un provider con il suo indirizzo di ritorno. */
    record ProviderRow(ProviderConfig config, String redirectUri) {
    }

    private final IOAuthAccess access;
    private final IOAuthProviders providers;

    OAuthSettingsAdvice(IOAuthAccess access, IOAuthProviders providers) {
        this.access = access;
        this.providers = providers;
    }

    /** Lazy: i dati si leggono al rendering, dopo l'eventuale salvataggio (un {@code @ModelAttribute} eager girerebbe prima e mostrerebbe lo stato vecchio). */
    @ModelAttribute("oauth2")
    Lazy view(HttpServletRequest request) {
        return onSettings(request) ? new Lazy(request) : null;
    }

    /** I getter sono letti da Thymeleaf. */
    final class Lazy {
        private final HttpServletRequest request;

        private Lazy(HttpServletRequest request) {
            this.request = request;
        }

        public GateStatus getStatus() {
            return access.status();
        }

        public List<ProviderRow> getProviders() {
            String base = ServletUriComponentsBuilder.fromContextPath(request).toUriString();
            return providers.list().stream().map(config -> new ProviderRow(config, base + "/login/oauth2/code/" + config.slug())).toList();
        }
    }

    private static boolean onSettings(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/settings") || path.startsWith("/settings/");
    }
}
