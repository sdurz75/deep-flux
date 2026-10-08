package org.dual.hexa.oauth2.login.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthProvider;
import org.dual.hexa.oauth2.login.port.out.IProviderStore;
import org.springframework.stereotype.Component;

@Component
class JpaProviderStore implements IProviderStore {

    private final OAuthProviderRepository repository;

    JpaProviderStore(OAuthProviderRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<OAuthProvider> findAll() {
        return repository.findAllByOrderBySlugAsc();
    }

    @Override
    public Optional<OAuthProvider> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<OAuthProvider> findBySlug(String slug) {
        return repository.findBySlug(slug);
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public OAuthProvider save(OAuthProvider provider) {
        return repository.save(provider);
    }

    @Override
    public void delete(OAuthProvider provider) {
        repository.delete(provider);
    }
}
