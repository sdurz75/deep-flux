package org.hexa.app.credits.port.out;

import java.util.Optional;

import org.hexa.app.credits.domain.ReplicateBalanceAnchor;

public interface IReplicateBalanceStore {

    Optional<ReplicateBalanceAnchor> find();

    /** Sostituisce l'ancora (riga unica). */
    ReplicateBalanceAnchor save(ReplicateBalanceAnchor anchor);
}
