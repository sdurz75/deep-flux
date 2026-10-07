package org.hexa.app.credits.adapter.out.persistence;

import java.util.Optional;

import org.hexa.app.credits.domain.ReplicateBalanceAnchor;
import org.hexa.app.credits.port.out.IReplicateBalanceStore;
import org.springframework.stereotype.Component;

/** {@link IReplicateBalanceStore} su Spring Data JPA (tabella {@code replicate_balance_anchor}, riga unica). */
@Component
class JpaReplicateBalanceStore implements IReplicateBalanceStore {

    private final ReplicateBalanceRepository repository;

    JpaReplicateBalanceStore(ReplicateBalanceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ReplicateBalanceAnchor> find() {
        return repository.findById(ReplicateBalanceAnchor.SINGLE_ID);
    }

    @Override
    public ReplicateBalanceAnchor save(ReplicateBalanceAnchor anchor) {
        return repository.save(anchor);
    }
}
