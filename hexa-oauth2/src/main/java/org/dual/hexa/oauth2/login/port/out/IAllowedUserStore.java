package org.dual.hexa.oauth2.login.port.out;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedUser;

public interface IAllowedUserStore {

    List<AllowedUser> findAll();

    Optional<AllowedUser> findById(Long id);

    Optional<AllowedUser> findByKindAndValue(AllowKind kind, String value);

    long count();

    AllowedUser save(AllowedUser user);

    void delete(AllowedUser user);
}
