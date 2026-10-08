package org.dual.hexa.oauth2.login.adapter.out.tokens;

import java.util.List;
import org.dual.hexa.core.tokens.port.out.ITokenProviderCatalog;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.stereotype.Component;

/** Aggiunge a {@code /tokens} il servizio {@code OAUTH2}: il segreto di un client OIDC si salva li' (cifrato), non nella configurazione dei moduli. */
@Component
class OAuthTokenProviders implements ITokenProviderCatalog {

    @Override
    public List<String> providers() {
        return List.of(IOAuthProviders.TOKEN_PROVIDER);
    }
}
