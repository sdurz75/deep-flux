package org.dual.hexa.app.generation.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.ModelUsage;
import org.dual.hexa.app.generation.domain.StatusCount;
import org.dual.hexa.app.generation.port.out.IGenerationStatsStore;
import org.dual.hexa.app.generation.port.out.IGenerationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Le query di aggregazione delle statistiche contro il Postgres vero (JPQL con costruttori e group by). */
@SpringBootTest
class GenerationStatsStoreTest {

    @Autowired
    private IGenerationStatsStore stats;

    @Autowired
    private IGenerationStore store;

    @BeforeEach
    @AfterEach
    void clean() {
        store.deleteAll();
    }

    private Generation generation(String model, GenerationStatus status, GenerationKind kind, String cost, String... favourites) {
        Generation g = new Generation("ext-" + System.nanoTime(), model, "v", "prompt", "{}");
        g.setStatus(status);
        g.setKind(kind);
        g.setImageFilenames(List.of("f-" + System.nanoTime() + ".png"));
        if (cost != null) {
            g.setCostUsd(new BigDecimal(cost));
        }
        if (favourites.length > 0) {
            g.setFavouriteFilenames(Set.of(g.getImageFilenames().get(0)));
        }
        return store.save(g);
    }

    @Test
    void aggregatesStatusKindModelsCostAndFavouritesOfGeneratedOnly() {
        generation("owner/a", GenerationStatus.SUCCEEDED, GenerationKind.IMAGE, "0.10", "star");
        generation("owner/a", GenerationStatus.SUCCEEDED, GenerationKind.IMAGE, "0.20");
        generation("owner/b", GenerationStatus.SUCCEEDED, GenerationKind.VIDEO, "1.00");
        generation("owner/a", GenerationStatus.FAILED, GenerationKind.IMAGE, null);
        generation("owner/b", GenerationStatus.PROCESSING, GenerationKind.IMAGE, null);
        store.save(Generation.imported("imp.png", Instant.now()));

        assertThat(stats.countByStatus()).containsExactlyInAnyOrder(new StatusCount(GenerationStatus.SUCCEEDED, 3),
                new StatusCount(GenerationStatus.FAILED, 1), new StatusCount(GenerationStatus.PROCESSING, 1));
        assertThat(stats.countSucceeded(GenerationKind.IMAGE)).isEqualTo(2);
        assertThat(stats.countSucceeded(GenerationKind.VIDEO)).isEqualTo(1);
        assertThat(stats.countFavouriteFiles()).isEqualTo(1);

        List<ModelUsage> top = stats.topModelsSince(Instant.EPOCH, 5);
        assertThat(top).extracting(ModelUsage::model).containsExactly("owner/a", "owner/b");
        assertThat(top.get(0).count()).isEqualTo(3);
        assertThat(top.get(0).costUsd()).isEqualByComparingTo("0.30");
        assertThat(top.get(1).costUsd()).isEqualByComparingTo("1.00");
        assertThat(stats.topModelsSince(Instant.EPOCH, 1)).hasSize(1);

        assertThat(stats.findActivitySince(Instant.EPOCH)).hasSize(5);
        assertThat(stats.findActivitySince(Instant.now().plusSeconds(60))).isEmpty();
        assertThat(stats.sumCostSince(Instant.EPOCH)).isEqualByComparingTo("1.30");
    }

    @Test
    void importedImagesAreCountedApartAndEmptyDbGivesZeros() {
        assertThat(stats.countByStatus()).isEmpty();
        assertThat(stats.countImported()).isZero();
        assertThat(stats.sumCostSince(Instant.EPOCH)).isEqualByComparingTo("0");

        Generation imported = Generation.imported("imp.png", Instant.now());
        imported.setStatus(GenerationStatus.SUCCEEDED);
        store.save(imported);

        assertThat(stats.countImported()).isEqualTo(1);
        assertThat(stats.countByStatus()).isEmpty(); // le importate non sono "generazioni lanciate"
    }
}
