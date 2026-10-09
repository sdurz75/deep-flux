package org.dual.hexa.app.generation.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.dual.hexa.app.generation.domain.ActivityRow;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.ModelUsage;
import org.dual.hexa.app.generation.domain.StatusCount;

/** Letture aggregate sulle generazioni lanciate dall'app (origine GENERATED), solo per le statistiche. */
public interface IGenerationStatsStore {

    /** Generazioni lanciate dall'app per stato (solo gli stati presenti). */
    List<StatusCount> countByStatus();

    /** Generazioni riuscite lanciate dall'app di quel tipo. */
    long countSucceeded(GenerationKind kind);

    /** Immagini importate (analisi a parte). */
    long countImported();

    /** File con la star (uno per file, non per generazione). */
    long countFavouriteFiles();

    /** Istante, esito e costo di ogni generazione lanciata da {@code since} in poi. */
    List<ActivityRow> findActivitySince(Instant since);

    /** I {@code limit} modelli piu' usati da {@code since}, dal piu' usato. */
    List<ModelUsage> topModelsSince(Instant since, int limit);

    /** Costo stimato delle generazioni create da {@code since}. */
    BigDecimal sumCostSince(Instant since);
}
