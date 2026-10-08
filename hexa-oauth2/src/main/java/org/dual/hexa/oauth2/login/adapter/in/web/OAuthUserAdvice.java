package org.dual.hexa.oauth2.login.adapter.in.web;

import java.net.URI;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** I dati del fragment {@code oauth2-user :: chip}: l'utente connesso con OAuth2/OIDC (claim standard {@code name}, {@code email}, {@code picture}), se c'e'. */
@ControllerAdvice
class OAuthUserAdvice {

    /** {@code picture} e' null se manca o non e' un URL https. */
    record Me(String name, String email, String picture, String initials, String provider) {
    }

    @ModelAttribute("oauth2Me")
    Me me() {
        return current();
    }

    static Me current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken token) || !token.isAuthenticated() || !(token.getPrincipal() instanceof OidcUser user)) {
            return null;
        }
        String email = user.getEmail();
        String name = user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName() : email;
        return new Me(name, email, https(user.getPicture()), initials(name), token.getAuthorizedClientRegistrationId());
    }

    private static String https(String url) {
        try {
            return url != null && "https".equalsIgnoreCase(URI.create(url).getScheme()) ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("[\\s@.]+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase(Locale.ROOT);
    }
}
