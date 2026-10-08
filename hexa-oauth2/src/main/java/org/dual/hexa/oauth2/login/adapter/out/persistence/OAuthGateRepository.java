package org.dual.hexa.oauth2.login.adapter.out.persistence;

import org.dual.hexa.oauth2.login.domain.OAuthGate;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code IGateStore}. */
interface OAuthGateRepository extends JpaRepository<OAuthGate, Long> {
}
