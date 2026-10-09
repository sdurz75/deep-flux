package org.dual.hexa.app.training.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.hexa.app.training.domain.HfStatus;
import org.dual.hexa.app.training.domain.ModelStatus;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface TrainingRepository extends JpaRepository<Training, Long> {

    /** Lo storico, l'ultimo lanciato prima; a parita' di istante il piu' recente. */
    Page<Training> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<Training> findByStatusIn(Collection<TrainingStatus> statuses);

    @Query("""
            select t from Training t
            where t.status = org.dual.hexa.app.training.domain.TrainingStatus.SUCCEEDED and t.completedAt >= :since
              and (t.presetId is null or t.modelStatus = :pendingModel or t.hfStatus = :pendingHf)
            order by t.id""")
    List<Training> findResultsToComplete(@Param("since") Instant since, @Param("pendingModel") ModelStatus pendingModel, @Param("pendingHf") HfStatus pendingHf);

    @Query("select t.status, count(t) from Training t group by t.status")
    List<Object[]> countGroupedByStatus();

    Optional<Training> findBySnapshotDatasetId(Long snapshotDatasetId);
}
