package org.hexa.app.credits.adapter.out.persistence;

import org.hexa.app.credits.domain.ReplicateBalanceAnchor;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface ReplicateBalanceRepository extends JpaRepository<ReplicateBalanceAnchor, Long> {
}
