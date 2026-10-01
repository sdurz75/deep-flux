package org.dual.replicate.repository;

import java.util.List;

import org.dual.replicate.domain.ApiToken;
import org.dual.replicate.domain.ApiTokenProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    List<ApiToken> findAllByOrderByProviderAscNameAsc();

    List<ApiToken> findAllByProviderOrderByNameAsc(ApiTokenProvider provider);

    boolean existsByProviderAndNameIgnoreCase(ApiTokenProvider provider, String name);

    boolean existsByProviderAndNameIgnoreCaseAndIdNot(ApiTokenProvider provider, String name, Long id);
}
