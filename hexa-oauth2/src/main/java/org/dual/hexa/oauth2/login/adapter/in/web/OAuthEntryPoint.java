package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Cosa risponde il cancello a chi non e' autenticato, con le stesse regole del PIN: una navigazione riceve un redirect alla pagina di accesso; htmx un 401 con
 * {@code HX-Redirect} (tranne se si trova gia' sulla pagina di accesso, o ricaricherebbe all'infinito: un redirect a Google dentro un XHR non funzionerebbe);
 * il resto (fetch, SSE, immagini) un 401 secco.
 */
@Component
class OAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            return; // flusso SSE gia' avviato (dispatch ASYNC di chiusura): niente da rispondere
        }
        String login = request.getContextPath() + OAuthRequests.LOGIN_PATH;
        response.setHeader("Cache-Control", "no-store");
        if (OAuthRequests.isHtmx(request)) {
            String current = OAuthRequests.currentPathOf(request);
            if (current != null && !current.equals(OAuthRequests.LOGIN_PATH)) {
                response.setHeader("HX-Redirect", login);
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (OAuthRequests.isNavigation(request)) {
            response.sendRedirect(login);
            return;
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
