package org.dual.hexa.oauth2.login.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.dual.hexa.core.web.ILayoutContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Innesta nella toolbar del layout (dopo la campanella) l'avatar dell'utente connesso, solo se c'e' una sessione OAuth2. */
@Component
@Order(30)
class OAuthLayoutContributor implements ILayoutContributor {

    @Override
    public List<String> toolbar(HttpServletRequest request) {
        return OAuthUserAdvice.current() == null ? List.of() : List.of("fragments/core/oauth2-user :: chip");
    }
}
