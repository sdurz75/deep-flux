package org.dual.hexa.core.lock.adapter.out.persistence;

import org.dual.hexa.core.lock.domain.AppLock;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code ILockStore}. */
interface AppLockRepository extends JpaRepository<AppLock, Long> {
}
