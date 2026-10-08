package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/** Piccole utilita' sulle richieste, le stesse regole del cancello del PIN ({@code LockInterceptor}): chi e' una navigazione, chi e' htmx, dove si trova. */
final class OAuthRequests {

    static final String LOGIN_PATH = "/oauth2/login";

    private OAuthRequests() {
    }

    static boolean isHtmx(HttpServletRequest request) {
        return "true".equalsIgnoreCase(request.getHeader("HX-Request"));
    }

    static boolean isNavigation(HttpServletRequest request) {
        if (isHtmx(request)) {
            return false;
        }
        if ("navigate".equals(request.getHeader("Sec-Fetch-Mode"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    /** Il path della richiesta senza context path. */
    static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context.isEmpty() || !uri.startsWith(context) ? uri : uri.substring(context.length());
    }

    /** Il path (senza context path) dell'URL assoluto {@code HX-Current-URL}, {@code null} se manca o non e' interpretabile. */
    static String currentPathOf(HttpServletRequest request) {
        String url = request.getHeader("HX-Current-URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String path = URI.create(url).getRawPath();
            String context = request.getContextPath();
            if (path == null) {
                return null;
            }
            return !context.isEmpty() && path.startsWith(context) ? path.substring(context.length()) : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
