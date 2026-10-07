package org.hexa.core.tokens.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.hexa.core.tokens.domain.ApiToken;
import org.hexa.core.tokens.port.out.IApiTokenStore;
import org.springframework.stereotype.Component;

/** {@link IApiTokenStore} su Spring Data JPA (tabella {@code api_token}). */
@Component
class JpaApiTokenStore implements IApiTokenStore {

    private final ApiTokenRepository repository;

    JpaApiTokenStore(ApiTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public ApiToken save(ApiToken token) {
        return repository.save(token);
    }

    @Override
    public Optional<ApiToken> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public void delete(ApiToken token) {
        repository.delete(token);
    }

    @Override
    public List<ApiToken> findAllOrdered() {
        return repository.findAllByOrderByProviderAscNameAsc();
    }

    @Override
    public List<ApiToken> findByProvider(String provider) {
        return repository.findAllByProviderOrderByNameAsc(provider);
    }

    @Override
    public boolean existsByProviderAndName(String provider, String name) {
        return repository.existsByProviderAndNameIgnoreCase(provider, name);
    }

    @Override
    public boolean existsByProviderAndNameExcluding(String provider, String name, Long excludedId) {
        return repository.existsByProviderAndNameIgnoreCaseAndIdNot(provider, name, excludedId);
    }

    @Override
    public List<ApiToken> findAll() {
        return repository.findAll();
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }
}
