package org.dual.replicate.app.training.port.out;

import java.util.Optional;

import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.core.kernel.Paged;

/** Persistenza dei dataset di addestramento (con le loro immagini). */
public interface ITrainingDatasetStore {

    /** Salva il dataset E le sue immagini (aggiunte e tolte): un'unica transazione. */
    TrainingDataset save(TrainingDataset dataset);

    Optional<TrainingDataset> findById(Long id);

    /** Le bozze ({@code frozen = false}), l'ultima modificata prima. */
    Paged<TrainingDataset> findDraftsPage(int pageIndex, int pageSize);

    void delete(TrainingDataset dataset);

    /** Svuota le tabelle (test e reset). */
    void deleteAll();
}
