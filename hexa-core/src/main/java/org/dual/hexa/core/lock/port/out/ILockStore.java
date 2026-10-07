package org.dual.hexa.core.lock.port.out;

import java.util.Optional;
import org.dual.hexa.core.lock.domain.AppLock;

/** Persistenza dell'unica riga del blocco. */
public interface ILockStore {

    Optional<AppLock> find();

    AppLock save(AppLock lock);

    void delete();
}
