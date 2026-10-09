package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.oauth2.login.domain.ConfigKeys;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * La durata della sessione di chi e' autenticato, a cancello acceso: inattivita' massima scorrevole ({@code session-days}), tetto assoluto dal primo accesso
 * ({@code session-max-days}, 0 = nessuno) e cookie persistente ({@code session-remember}: senza Max-Age il browser lo butta alla chiusura, PWA compresa). I
 * valori si leggono a ogni richiesta da {@code IModuleSettings}, quindi valgono senza riavvio. Il cookie si ri-emette al massimo una volta l'ora per sessione.
 * I token del provider non c'entrano: dopo l'accesso non vengono piu' usati.
 */
class SessionLifetimeFilter extends OncePerRequestFilter {

    static final String LOGIN_AT = "hexa.oauth2.loginAt";
    static final String COOKIE_AT = "hexa.oauth2.cookieAt";
    private static final long DAY_SECONDS = 86_400;
    private static final long COOKIE_REFRESH_MILLIS = 3_600_000;

    private final IOAuthAccess access;
    private final IModuleSettings settings;

    SessionLifetimeFilter(IOAuthAccess access, IModuleSettings settings) {
        this.access = access;
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !access.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (session != null && authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            apply(request, response, session);
        }
        chain.doFilter(request, response);
    }

    private void apply(HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        ModuleValues values = settings.values(ConfigKeys.MODULE);
        long now = System.currentTimeMillis();
        long loginAt = session.getAttribute(LOGIN_AT) instanceof Long stored ? stored : now;
        if (!(session.getAttribute(LOGIN_AT) instanceof Long)) {
            session.setAttribute(LOGIN_AT, now);
        }
        long maxDays = values.getInt(ConfigKeys.SESSION_MAX_DAYS);
        if (maxDays > 0 && now - loginAt > maxDays * DAY_SECONDS * 1000) {
            session.invalidate();
            return;
        }
        long seconds = values.getInt(ConfigKeys.SESSION_DAYS) * DAY_SECONDS;
        if (session.getMaxInactiveInterval() != seconds) {
            session.setMaxInactiveInterval((int) seconds);
        }
        if (values.getBoolean(ConfigKeys.SESSION_REMEMBER)
                && !(session.getAttribute(COOKIE_AT) instanceof Long at && now - at < COOKIE_REFRESH_MILLIS)) {
            session.setAttribute(COOKIE_AT, now);
            response.addHeader("Set-Cookie", cookie(request, session.getId(), seconds));
        }
    }

    static String cookie(HttpServletRequest request, String id, long seconds) {
        String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        return "JSESSIONID=" + id + "; Max-Age=" + seconds + "; Path=" + path + "; HttpOnly; SameSite=Lax" + (request.isSecure() ? "; Secure" : "");
    }
}
