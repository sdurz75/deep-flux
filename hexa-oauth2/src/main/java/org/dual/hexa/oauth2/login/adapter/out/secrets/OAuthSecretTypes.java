package org.dual.hexa.oauth2.login.adapter.out.secrets;

import java.util.List;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.dual.hexa.oauth2.login.port.in.IOAuthProviders;
import org.springframework.stereotype.Component;

/** Il tipo {@code OAUTH2_CLIENT}: i segreti dei client OIDC sono dei campi {@code SECRET} di questo modulo, visibili in {@code /secrets} ma non modificabili da li'. */
@Component
class OAuthSecretTypes implements ISecretTypeCatalog {

    @Override
    public List<SecretType> types() {
        return List.of(new SecretType(IOAuthProviders.SECRET_TYPE, "secrets.type.OAUTH2_CLIENT", true));
    }
}
