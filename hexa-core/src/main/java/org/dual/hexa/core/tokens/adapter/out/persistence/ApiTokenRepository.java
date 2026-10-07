package org.dual.hexa.core.tokens.adapter.out.persistence;

import java.util.List;

import org.dual.hexa.core.tokens.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code IApiTokenStore}. */
interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    List<ApiToken> findAllByOrderByProviderAscNameAsc();

    List<ApiToken> findAllByProviderOrderByNameAsc(String provider);

    boolean existsByProviderAndNameIgnoreCase(String provider, String name);

    boolean existsByProviderAndNameIgnoreCaseAndIdNot(String provider, String name, Long id);
}
