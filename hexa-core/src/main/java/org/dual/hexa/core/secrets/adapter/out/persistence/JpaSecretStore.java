package org.dual.hexa.core.secrets.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.core.secrets.domain.Secret;
import org.dual.hexa.core.secrets.port.out.ISecretStore;
import org.springframework.stereotype.Component;

/** {@link ISecretStore} su Spring Data JPA (tabella {@code secret}). */
@Component
class JpaSecretStore implements ISecretStore {

    private final SecretRepository repository;

    JpaSecretStore(SecretRepository repository) {
        this.repository = repository;
    }

    @Override
    public Secret save(Secret secret) {
        return repository.save(secret);
    }

    @Override
    public Optional<Secret> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public void delete(Secret secret) {
        repository.delete(secret);
    }

    @Override
    public List<Secret> findAllOrdered() {
        return repository.findAllByOrderByTypeAscNameAsc();
    }

    @Override
    public List<Secret> findByType(String type) {
        return repository.findAllByTypeOrderByNameAsc(type);
    }

    @Override
    public Optional<Secret> findByTypeAndName(String type, String name) {
        return repository.findByTypeAndName(type, name);
    }

    @Override
    public List<Secret> findByTypeAndNamePrefix(String type, String prefix) {
        return repository.findAllByTypeAndNameStartingWith(type, prefix);
    }

    @Override
    public boolean existsByTypeAndName(String type, String name) {
        return repository.existsByTypeAndNameIgnoreCase(type, name);
    }

    @Override
    public boolean existsByTypeAndNameExcluding(String type, String name, Long excludedId) {
        return repository.existsByTypeAndNameIgnoreCaseAndIdNot(type, name, excludedId);
    }

    @Override
    public List<Secret> findAll() {
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
