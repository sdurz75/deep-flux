package org.dual.hexa.oauth2.login.adapter.out.persistence;

import org.dual.hexa.oauth2.login.domain.OAuthLogin;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code ILoginStore}. */
interface OAuthLoginRepository extends JpaRepository<OAuthLogin, String> {
}
