package org.dual.replicate.app.generation.adapter.out.persistence;

import java.util.List;

import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.port.out.IModelStore;
import org.springframework.stereotype.Component;

/** {@link IModelStore} su Spring Data JPA (tabella {@code replicate_model}). */
@Component
class JpaModelStore implements IModelStore {

    private final ReplicateModelRepository repository;

    JpaModelStore(ReplicateModelRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ReplicateModel> findActiveOrdered() {
        return repository.findByActiveTrueOrderBySortOrderAsc();
    }

    @Override
    public boolean exists(String owner, String name) {
        return repository.findByOwnerAndName(owner, name).isPresent();
    }

    @Override
    public int nextSortOrder() {
        return repository.findMaxSortOrder().map(max -> max + 1).orElse(0);
    }

    @Override
    public ReplicateModel save(ReplicateModel model) {
        return repository.save(model);
    }
}
