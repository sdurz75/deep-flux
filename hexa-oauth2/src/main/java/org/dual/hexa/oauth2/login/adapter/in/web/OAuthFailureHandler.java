package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.oauth2.login.domain.OAuthEventSource;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Un accesso fallito torna alla pagina di accesso. Un utente non ammesso ({@code access_denied}) e' un esito atteso e gia' annotato dal servizio (senza dire
 * perche': l'esito non rivela quali account esistono); qualunque altro guasto (id token non valido, scambio del codice rifiutato) lo registro qui, perche'
 * lo gestisco io.
 */
@Component
class OAuthFailureHandler implements AuthenticationFailureHandler {

    private final ISystemEvents events;

    OAuthFailureHandler(ISystemEvents events) {
        this.events = events;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        boolean denied = exception instanceof OAuth2AuthenticationException oauth && AllowlistOidcUserService.DENIED.equals(oauth.getError().getErrorCode());
        if (!denied) {
            events.record(OAuthEventSource.OAUTH2, "oauth2Login", exception);
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendRedirect(request.getContextPath() + OAuthRequests.LOGIN_PATH + (denied ? "?error=denied" : "?error=failed"));
    }
}
