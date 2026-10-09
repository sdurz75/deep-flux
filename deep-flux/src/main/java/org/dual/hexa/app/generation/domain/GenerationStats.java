package org.dual.hexa.app.generation.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Statistiche delle generazioni per la dashboard. Le generazioni sono quelle lanciate dall'app (le importate sono contate a parte).
 * {@code daily} copre ESATTAMENTE la finestra richiesta, un elemento per giorno, dal piu' vecchio a oggi, anche per i giorni senza attivita'.
 * I costi sono stime ({@link ReplicatePricing}): le generazioni di un modello senza regola non contano.
 *
 * @param inProgress   generazioni non ancora terminali
 * @param windowDays   ampiezza della finestra di {@code daily}, {@code topModels}, {@code costWindow}
 * @param costWindow   costo stimato nella finestra
 * @param costLast7    costo stimato negli ultimi 7 giorni (oggi compreso)
 * @param costTotal    costo stimato di sempre
 * @param topModels    i modelli piu' usati nella finestra, dal piu' usato
 */
public record GenerationStats(long images, long videos, long imported, long failed, long inProgress, long favourites,
                              int windowDays, BigDecimal costWindow, BigDecimal costLast7, BigDecimal costTotal,
                              List<DailyActivity> daily, List<ModelUsage> topModels) {

    /** Generazioni lanciate dall'app e finite (riuscite o fallite). */
    public long finished() {
        return images + videos + failed;
    }

    /** Quota di riuscite fra le finite, 0..100; {@code -1} se non ce n'e' ancora nessuna (niente "0%" fuorviante). */
    public int successPercent() {
        long finished = finished();
        return finished == 0 ? -1 : (int) Math.round(100.0 * (images + videos) / finished);
    }

    /** Generazioni lanciate nella finestra (riuscite + fallite). */
    public long launchedInWindow() {
        return daily.stream().mapToLong(DailyActivity::total).sum();
    }
}
