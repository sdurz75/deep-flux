package org.dual.hexa.oauth2.login.adapter.in.web;

import java.util.List;
import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Dove si applica la lista degli ammessi: DENTRO l'autenticazione, prima che il contesto di sicurezza venga salvato in sessione. Chi non e' ammesso non
 * ottiene mai una sessione autenticata (un handler di successo sarebbe troppo tardi). Le informazioni vengono dall'id token (firma, issuer, audience e
 * scadenza gia' verificati da Spring Security); nessuna chiamata a userinfo.
 */
@Component
class AllowlistOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String DENIED = "access_denied";

    private final IOAuthAccess access;

    AllowlistOidcUserService(IOAuthAccess access) {
        this.access = access;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcIdToken token = request.getIdToken();
        String issuer = token.getIssuer() == null ? request.getClientRegistration().getProviderDetails().getIssuerUri() : token.getIssuer().toString();
        AccessDecision decision = access.authorize(issuer, token.getEmail(), Boolean.TRUE.equals(token.getEmailVerified()), token.getSubject());
        if (!decision.allowed()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(DENIED, decision.reason().name(), null));
        }
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token);
    }
}
