package org.dual.replicate.app.training.adapter.out.persistence;

import org.dual.replicate.app.training.domain.TrainingDataset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface TrainingDatasetRepository extends JpaRepository<TrainingDataset, Long> {

    /** Le bozze (non gli snapshot), l'ultima modificata prima; a parita' di istante la piu' recente. */
    Page<TrainingDataset> findByFrozenFalseOrderByUpdatedAtDescIdDesc(Pageable pageable);
}
