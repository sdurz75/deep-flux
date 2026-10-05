package org.dual.replicate.app.training.adapter.out.persistence;

import java.util.Optional;

import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** {@link ITrainingDatasetStore} su Spring Data JPA (tabelle {@code training_dataset} e {@code training_image}). */
@Component
class JpaTrainingDatasetStore implements ITrainingDatasetStore {

    private final TrainingDatasetRepository repository;

    JpaTrainingDatasetStore(TrainingDatasetRepository repository) {
        this.repository = repository;
    }

    @Override
    public TrainingDataset save(TrainingDataset dataset) {
        return repository.save(dataset);
    }

    @Override
    public Optional<TrainingDataset> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Paged<TrainingDataset> findDraftsPage(int pageIndex, int pageSize) {
        Page<TrainingDataset> page = repository.findByFrozenFalseOrderByUpdatedAtDescIdDesc(PageRequest.of(pageIndex, pageSize));
        return new Paged<>(page.getContent(), pageIndex, pageSize, page.getTotalElements());
    }

    @Override
    public void delete(TrainingDataset dataset) {
        repository.delete(dataset);
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }
}
