package org.dual.hexa.core.lock.adapter.out.persistence;

import java.util.Optional;
import org.dual.hexa.core.lock.domain.AppLock;
import org.dual.hexa.core.lock.port.out.ILockStore;
import org.springframework.stereotype.Component;

@Component
class JpaLockStore implements ILockStore {

    private final AppLockRepository repository;

    JpaLockStore(AppLockRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AppLock> find() {
        return repository.findById(AppLock.SINGLE_ID);
    }

    @Override
    public AppLock save(AppLock lock) {
        return repository.save(lock);
    }

    @Override
    public void delete() {
        repository.deleteById(AppLock.SINGLE_ID);
    }
}
