package org.dual.hexa.app.training.adapter.out.persistence;

import java.util.List;

import org.dual.hexa.app.training.domain.PendingCaption;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface TrainingDatasetRepository extends JpaRepository<TrainingDataset, Long> {

    /** Le bozze (non gli snapshot), l'ultima modificata prima; a parita' di istante la piu' recente. */
    Page<TrainingDataset> findByFrozenFalseOrderByUpdatedAtDescIdDesc(Pageable pageable);

    /** Le immagini delle BOZZE con la didascalia automatica in sospeso (gli snapshot non cambiano piu'). */
    @Query("""
            select new org.dual.hexa.app.training.domain.PendingCaption(i.dataset.id, i.id)
            from TrainingImage i
            where i.captionStatus = org.dual.hexa.app.training.domain.CaptionStatus.PENDING and i.dataset.frozen = false
            order by i.id""")
    List<PendingCaption> findPendingCaptions();
}
