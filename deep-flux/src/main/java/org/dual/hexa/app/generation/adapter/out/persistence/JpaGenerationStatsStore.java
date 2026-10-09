package org.dual.hexa.app.generation.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.dual.hexa.app.generation.domain.ActivityRow;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationOrigin;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.ModelUsage;
import org.dual.hexa.app.generation.domain.StatusCount;
import org.dual.hexa.app.generation.port.out.IGenerationStatsStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link IGenerationStatsStore} su Spring Data JPA (tabella {@code generation}), sola lettura. */
@Component
@Transactional(readOnly = true)
class JpaGenerationStatsStore implements IGenerationStatsStore {

    private final GenerationStatsRepository repository;

    JpaGenerationStatsStore(GenerationStatsRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<StatusCount> countByStatus() {
        return repository.countGeneratedByStatus();
    }

    @Override
    public long countSucceeded(GenerationKind kind) {
        return repository.countByStatusAndKindAndOrigin(GenerationStatus.SUCCEEDED, kind, GenerationOrigin.GENERATED);
    }

    @Override
    public long countImported() {
        return repository.countByStatusAndOrigin(GenerationStatus.SUCCEEDED, GenerationOrigin.IMPORTED);
    }

    @Override
    public long countFavouriteFiles() {
        return repository.countFavouriteFiles();
    }

    @Override
    public List<ActivityRow> findActivitySince(Instant since) {
        return repository.findGeneratedSince(since);
    }

    @Override
    public List<ModelUsage> topModelsSince(Instant since, int limit) {
        return repository.topModelsSince(since, PageRequest.of(0, limit));
    }

    @Override
    public BigDecimal sumCostSince(Instant since) {
        return repository.sumCostSince(since);
    }
}
