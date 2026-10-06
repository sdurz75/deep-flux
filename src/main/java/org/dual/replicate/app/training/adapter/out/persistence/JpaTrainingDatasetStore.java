package org.dual.replicate.app.training.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.dual.replicate.app.training.domain.DatasetConflictException;
import org.dual.replicate.app.training.domain.PendingCaption;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link ITrainingDatasetStore} su Spring Data JPA (tabelle {@code training_dataset} e {@code training_image}). */
@Component
class JpaTrainingDatasetStore implements ITrainingDatasetStore {

    private final TrainingDatasetRepository repository;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    JpaTrainingDatasetStore(TrainingDatasetRepository repository, EntityManager entityManager, TransactionTemplate transaction) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.transaction = transaction;
    }

    /**
     * Salvare un dataset ESISTENTE fa SEMPRE salire la sua versione, anche se a cambiare sono solo le immagini (una didascalia, un ritaglio): per un
     * figlio modificato Hibernate non e' tenuto a sporcare il padre, e senza un salto di versione una copia letta prima potrebbe ri-salvarsi sopra
     * senza che il blocco ottimistico se ne accorga, riportando in silenzio una didascalia appena arrivata a com'era. La copia vecchia invece fallisce
     * (la riconosce {@code merge}, che confronta la versione), e chi chiama rilegge e riapplica. Il conflitto esce come {@link DatasetConflictException}: la
     * transazione e' un {@code TransactionTemplate} (non {@code @Transactional}) perche' il conflitto puo' emergere anche al commit, che un {@code try} nel
     * corpo di un metodo transazionale non vedrebbe.
     */
    @Override
    public TrainingDataset save(TrainingDataset dataset) {
        try {
            return transaction.execute(status -> {
                boolean existing = dataset.getId() != null;
                TrainingDataset saved = repository.save(dataset);
                if (existing) {
                    entityManager.lock(saved, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
                }
                return saved;
            });
        } catch (OptimisticLockingFailureException e) {
            throw new DatasetConflictException(e.getMessage(), e);
        }
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
    public List<PendingCaption> findPendingCaptions() {
        return repository.findPendingCaptions();
    }

    @Override
    public void delete(TrainingDataset dataset) {
        try {
            repository.delete(dataset);
        } catch (OptimisticLockingFailureException e) {
            throw new DatasetConflictException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }
}
