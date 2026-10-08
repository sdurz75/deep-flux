package org.dual.hexa.oauth2.login.port.out;

import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthLogin;

/** Gli accessi riusciti (uno per email). */
public interface ILoginStore {

    Optional<OAuthLogin> find(String email);

    OAuthLogin save(OAuthLogin login);

    long count();

    void deleteAll();
}
