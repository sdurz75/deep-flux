package org.dual.hexa.oauth2.login.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedUser;
import org.dual.hexa.oauth2.login.port.out.IAllowedUserStore;
import org.springframework.stereotype.Component;

@Component
class JpaAllowedUserStore implements IAllowedUserStore {

    private final AllowedUserRepository repository;

    JpaAllowedUserStore(AllowedUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<AllowedUser> findAll() {
        return repository.findAllByOrderByKindAscValueAsc();
    }

    @Override
    public Optional<AllowedUser> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<AllowedUser> findByKindAndValue(AllowKind kind, String value) {
        return repository.findByKindAndValue(kind, value);
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public AllowedUser save(AllowedUser user) {
        return repository.save(user);
    }

    @Override
    public void delete(AllowedUser user) {
        repository.delete(user);
    }
}
