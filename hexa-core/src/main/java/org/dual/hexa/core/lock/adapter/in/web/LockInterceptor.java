package org.dual.hexa.core.lock.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.dual.hexa.core.lock.port.in.ILock;
import org.dual.hexa.core.lock.port.in.ILockExemptPaths;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Il cancello vero: con il PIN impostato e la sessione bloccata NIENTE passa (HTML, htmx, {@code /events}, {@code /images/**}), salvo i path esenti (quelli del blocco stesso, quelli delle librerie via {@code ILockExemptPaths}, {@code app.lock.exempt-paths}). Un
 * blocco solo lato client sarebbe cosmetico. La risposta dipende da chi chiede: una navigazione riceve un redirect a {@code /unlock?next=...}, htmx un 401
 * con {@code HX-Redirect} (tranne se si trova gia' su {@code /unlock}, o ricaricherebbe all'infinito), il resto (fetch, SSE, immagini) un 401 secco.
 */
@Component
class LockInterceptor implements HandlerInterceptor {

    static final String UNLOCK_PATH = "/unlock";
    private static final List<String> EXEMPT = List.of(UNLOCK_PATH, "/lock/now", "/js/", "/css/", "/error", "/favicon.ico");

    private final ILock lock;
    private final LockSessions sessions;
    private final List<String> exempt = new ArrayList<>(EXEMPT);

    LockInterceptor(ILock lock, LockSessions sessions, ObjectProvider<ILockExemptPaths> libraries, @Value("${app.lock.exempt-paths:}") String extraExemptPaths) {
        this.lock = lock;
        this.sessions = sessions;
        libraries.orderedStream().forEach(library -> exempt.addAll(library.paths()));
        Arrays.stream(extraExemptPaths.split(",")).map(String::strip).filter(path -> !path.isEmpty()).forEach(exempt::add);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!lock.isEnabled() || isExempt(pathOf(request)) || sessions.isUnlocked(request)) {
            return true;
        }
        boolean htmx = "true".equalsIgnoreCase(request.getHeader("HX-Request"));
        if (htmx) {
            String current = pathAndQueryOf(request.getHeader("HX-Current-URL"), request.getContextPath());
            if (current != null && !isExempt(current.split("\\?", 2)[0])) {
                response.setHeader("HX-Redirect", unlockUrl(request, current));
            }
            response.setHeader("Cache-Control", "no-store");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (isNavigation(request)) {
            String next = "GET".equals(request.getMethod()) ? pathOf(request) + (request.getQueryString() == null ? "" : "?" + request.getQueryString()) : "/";
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(unlockUrl(request, next));
            return false;
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    private boolean isExempt(String path) {
        return exempt.stream().anyMatch(prefix -> prefix.endsWith("/") ? path.startsWith(prefix) : path.equals(prefix));
    }

    private static boolean isNavigation(HttpServletRequest request) {
        if ("navigate".equals(request.getHeader("Sec-Fetch-Mode"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context.isEmpty() || !uri.startsWith(context) ? uri : uri.substring(context.length());
    }

    /** Percorso (e query) di un URL assoluto del browser, senza context path; {@code null} se manca o non e' interpretabile. */
    private static String pathAndQueryOf(String url, String contextPath) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
            return (path.isEmpty() ? "/" : path) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String unlockUrl(HttpServletRequest request, String next) {
        return request.getContextPath() + UNLOCK_PATH + "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
    }
}
