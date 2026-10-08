package org.dual.hexa.oauth2.login.port.out;

import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthGate;

/** Persistenza dell'unica riga del cancello. */
public interface IGateStore {

    Optional<OAuthGate> find();

    OAuthGate save(OAuthGate gate);
}
