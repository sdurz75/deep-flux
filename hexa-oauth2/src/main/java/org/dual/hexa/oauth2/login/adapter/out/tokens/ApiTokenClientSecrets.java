package org.dual.hexa.oauth2.login.adapter.out.tokens;

import org.dual.hexa.core.tokens.domain.TokenException;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.dual.hexa.oauth2.login.port.out.IClientSecrets;
import org.springframework.stereotype.Component;

@Component
class ApiTokenClientSecrets implements IClientSecrets {

    private final IApiTokens tokens;

    ApiTokenClientSecrets(IApiTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean exists(Long tokenId) {
        try {
            IApiTokens.TokenView view = tokens.get(tokenId);
            return IOAuthProviders.TOKEN_PROVIDER.equals(view.provider()) && view.status() != IApiTokens.Status.EXPIRED;
        } catch (TokenException e) {
            return false;
        }
    }

    @Override
    public String resolve(Long tokenId) {
        try {
            return tokens.resolve(tokenId, IOAuthProviders.TOKEN_PROVIDER);
        } catch (TokenException e) {
            throw new OAuthException(e.getMessage(), e, e.kind());
        }
    }
}
