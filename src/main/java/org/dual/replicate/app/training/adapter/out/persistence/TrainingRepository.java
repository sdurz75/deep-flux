package org.dual.replicate.app.training.adapter.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface TrainingRepository extends JpaRepository<Training, Long> {

    /** Lo storico, l'ultimo lanciato prima; a parita' di istante il piu' recente. */
    Page<Training> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<Training> findByStatusIn(Collection<TrainingStatus> statuses);

    Optional<Training> findBySnapshotDatasetId(Long snapshotDatasetId);
}
