package org.dual.hexa.oauth2.login.adapter.out.persistence;

import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthLogin;
import org.dual.hexa.oauth2.login.port.out.ILoginStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaLoginStore implements ILoginStore {

    private final OAuthLoginRepository repository;

    JpaLoginStore(OAuthLoginRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OAuthLogin> find(String email) {
        return repository.findById(email);
    }

    @Override
    public OAuthLogin save(OAuthLogin login) {
        return repository.save(login);
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW) // chiamato da afterCommit: la transazione chiamante e' finita
    public void deleteAll() {
        repository.deleteAllInBatch();
    }
}
