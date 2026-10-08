package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Difesa CSRF del cancello, SOLO a cancello acceso. Il token CSRF di Spring Security romperebbe ogni POST di htmx, i {@code fetch} degli script e l'upload della
 * maschera; qui un browser che invia una richiesta che modifica deve dichiarare di venire dalla stessa origine: {@code Sec-Fetch-Site} {@code same-origin} (o
 * {@code none}), oppure, dove manca, un {@code Origin} con lo stesso host della richiesta. Client senza nessuno dei due (curl, script) non sono browser con
 * cookie da sfruttare e passano. A cancello spento non fa nulla.
 */
class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final IOAuthAccess access;

    SameOriginFilter(IOAuthAccess access) {
        this.access = access;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE.contains(request.getMethod()) || !access.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!crossSite(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    private static boolean crossSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return !("same-origin".equals(site) || "none".equals(site));
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return false;
        }
        try {
            String forwarded = request.getHeader("X-Forwarded-Host");
            String host = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].strip() : request.getHeader("Host");
            return host == null || !host.equalsIgnoreCase(URI.create(origin).getAuthority());
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
