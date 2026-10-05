package org.dual.replicate.app.training.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.port.out.ITrainingStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** {@link ITrainingStore} su Spring Data JPA (tabella {@code training}). */
@Component
class JpaTrainingStore implements ITrainingStore {

    private final TrainingRepository repository;

    JpaTrainingStore(TrainingRepository repository) {
        this.repository = repository;
    }

    @Override
    public Training save(Training training) {
        return repository.save(training);
    }

    @Override
    public Optional<Training> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Paged<Training> findPage(int pageIndex, int pageSize) {
        Page<Training> page = repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(pageIndex, pageSize));
        return new Paged<>(page.getContent(), pageIndex, pageSize, page.getTotalElements());
    }

    @Override
    public List<Training> findByStatusIn(Collection<TrainingStatus> statuses) {
        return repository.findByStatusIn(statuses);
    }

    @Override
    public Optional<Training> findBySnapshotDatasetId(Long snapshotDatasetId) {
        return repository.findBySnapshotDatasetId(snapshotDatasetId);
    }

    @Override
    public void delete(Training training) {
        repository.delete(training);
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }
}
