package org.dual.hexa.app.generation.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.dual.hexa.app.generation.domain.ActivityRow;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationOrigin;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.ModelUsage;
import org.dual.hexa.app.generation.domain.StatusCount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Query di aggregazione per le statistiche. Dettaglio di persistenza: fuori si usa {@code IGenerationStatsStore}. */
interface GenerationStatsRepository extends Repository<Generation, Long> {

    @Query("select new org.dual.hexa.app.generation.domain.StatusCount(g.status, count(g)) from Generation g "
            + "where g.origin = org.dual.hexa.app.generation.domain.GenerationOrigin.GENERATED group by g.status")
    List<StatusCount> countGeneratedByStatus();

    long countByStatusAndKindAndOrigin(GenerationStatus status, GenerationKind kind, GenerationOrigin origin);

    long countByStatusAndOrigin(GenerationStatus status, GenerationOrigin origin);

    @Query("select count(f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.dual.hexa.app.generation.domain.GenerationStatus.SUCCEEDED")
    long countFavouriteFiles();

    @Query("select new org.dual.hexa.app.generation.domain.ActivityRow(g.createdAt, g.status, g.costUsd) from Generation g "
            + "where g.origin = org.dual.hexa.app.generation.domain.GenerationOrigin.GENERATED and g.createdAt >= :since")
    List<ActivityRow> findGeneratedSince(@Param("since") Instant since);

    @Query("select new org.dual.hexa.app.generation.domain.ModelUsage(g.model, count(g), coalesce(sum(g.costUsd), 0)) from Generation g "
            + "where g.origin = org.dual.hexa.app.generation.domain.GenerationOrigin.GENERATED and g.model is not null "
            + "and g.createdAt >= :since group by g.model order by count(g) desc, g.model")
    List<ModelUsage> topModelsSince(@Param("since") Instant since, Pageable pageable);

    @Query("select coalesce(sum(g.costUsd), 0) from Generation g where g.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") Instant since);
}
