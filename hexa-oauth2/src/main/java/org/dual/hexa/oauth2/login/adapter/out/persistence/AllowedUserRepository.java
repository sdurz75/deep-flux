package org.dual.hexa.oauth2.login.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedUser;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code IAllowedUserStore}. */
interface AllowedUserRepository extends JpaRepository<AllowedUser, Long> {

    List<AllowedUser> findAllByOrderByKindAscValueAsc();

    Optional<AllowedUser> findByKindAndValue(AllowKind kind, String value);
}
