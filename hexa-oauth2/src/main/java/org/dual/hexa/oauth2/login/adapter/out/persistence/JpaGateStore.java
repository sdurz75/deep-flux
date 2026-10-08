package org.dual.hexa.oauth2.login.adapter.out.persistence;

import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthGate;
import org.dual.hexa.oauth2.login.port.out.IGateStore;
import org.springframework.stereotype.Component;

@Component
class JpaGateStore implements IGateStore {

    private final OAuthGateRepository repository;

    JpaGateStore(OAuthGateRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OAuthGate> find() {
        return repository.findById(OAuthGate.SINGLE_ID);
    }

    @Override
    public OAuthGate save(OAuthGate gate) {
        return repository.save(gate);
    }
}
