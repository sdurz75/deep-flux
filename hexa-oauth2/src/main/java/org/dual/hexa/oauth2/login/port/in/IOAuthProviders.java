package org.dual.hexa.oauth2.login.port.in;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;

/** I provider OIDC: quello d'ambiente (primo, in sola lettura) e quelli salvati nelle impostazioni del modulo. */
public interface IOAuthProviders {

    /** Il tipo di segreto dei client OIDC (etichetta {@code secrets.type.OAUTH2_CLIENT}). */
    String SECRET_TYPE = "OAUTH2_CLIENT";

    List<ProviderConfig> list();

    Optional<ProviderConfig> find(String slug);

    /** Il segreto del client in chiaro, solo per costruire la registrazione OIDC: vuoto se manca o non si decifra. Mai in log, eventi o Model. */
    Optional<String> clientSecret(String slug);
}
