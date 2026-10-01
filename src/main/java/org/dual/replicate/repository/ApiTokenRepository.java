package org.dual.replicate.repository;

import java.util.List;

import org.dual.replicate.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    List<ApiToken> findAllByOrderByProviderAscNameAsc();

    List<ApiToken> findAllByProviderOrderByNameAsc(String provider);

    boolean existsByProviderAndNameIgnoreCase(String provider, String name);

    boolean existsByProviderAndNameIgnoreCaseAndIdNot(String provider, String name, Long id);
}
