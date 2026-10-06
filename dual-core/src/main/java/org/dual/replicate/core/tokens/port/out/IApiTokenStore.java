package org.dual.replicate.core.tokens.port.out;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.core.tokens.domain.ApiToken;

/** Persistenza dei token API (cifrati). */
public interface IApiTokenStore {

    ApiToken save(ApiToken token);

    Optional<ApiToken> findById(Long id);

    void delete(ApiToken token);

    /** Tutti, ordinati per provider e nome. */
    List<ApiToken> findAllOrdered();

    /** Quelli di un provider, ordinati per nome. */
    List<ApiToken> findByProvider(String provider);

    boolean existsByProviderAndName(String provider, String name);

    boolean existsByProviderAndNameExcluding(String provider, String name, Long excludedId);

    List<ApiToken> findAll();

    long count();

    void deleteAll();
}
