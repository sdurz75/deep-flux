package org.dual.hexa.app.generation.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.dual.hexa.app.generation.domain.ActivityRow;
import org.dual.hexa.app.generation.domain.DailyActivity;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationStats;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.StatusCount;
import org.dual.hexa.app.generation.port.in.IGenerationStats;
import org.dual.hexa.app.generation.port.out.IGenerationStatsStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Statistiche delle generazioni. Il raggruppamento per giorno si fa qui, in Java, sulle righe della sola finestra richiesta:
 * resta portabile (nessuna funzione di data nel JPQL) e il "giorno" e' quello del fuso del {@link Clock}, non quello del DB.
 */
@Service
public class GenerationStatsService implements IGenerationStats {

    /** Quanti modelli elenca la dashboard. */
    static final int TOP_MODELS = 5;

    private final IGenerationStatsStore store;
    private final Clock clock;

    @Autowired
    public GenerationStatsService(IGenerationStatsStore store) {
        this(store, Clock.systemDefaultZone());
    }

    GenerationStatsService(IGenerationStatsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public GenerationStats stats(int windowDays) {
        if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("windowDays must be between 1 and " + MAX_WINDOW_DAYS + ": " + windowDays);
        }
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.minusDays(windowDays - 1L);
        Instant since = first.atStartOfDay(zone).toInstant();
        LocalDate firstOfLast7 = today.minusDays(6);

        List<ActivityRow> rows = store.findActivitySince(since);
        List<DailyActivity> daily = new ArrayList<>(windowDays);
        BigDecimal costWindow = BigDecimal.ZERO;
        BigDecimal costLast7 = BigDecimal.ZERO;
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            long succeeded = 0;
            long failed = 0;
            BigDecimal cost = BigDecimal.ZERO;
            for (ActivityRow row : rows) {
                if (!row.createdAt().atZone(zone).toLocalDate().equals(day)) {
                    continue;
                }
                if (row.status() == GenerationStatus.SUCCEEDED) {
                    succeeded++;
                } else if (row.status() == GenerationStatus.FAILED) {
                    failed++;
                }
                if (row.costUsd() != null) {
                    cost = cost.add(row.costUsd());
                }
            }
            daily.add(new DailyActivity(day, succeeded, failed, cost));
            costWindow = costWindow.add(cost);
            if (!day.isBefore(firstOfLast7)) {
                costLast7 = costLast7.add(cost);
            }
        }

        long failedTotal = 0;
        long inProgress = 0;
        for (StatusCount count : store.countByStatus()) {
            if (count.status() == GenerationStatus.FAILED) {
                failedTotal += count.count();
            } else if (count.status() == GenerationStatus.PENDING || count.status() == GenerationStatus.PROCESSING) {
                inProgress += count.count();
            }
        }
        return new GenerationStats(store.countSucceeded(GenerationKind.IMAGE), store.countSucceeded(GenerationKind.VIDEO),
                store.countImported(), failedTotal, inProgress, store.countFavouriteFiles(), windowDays, costWindow, costLast7,
                store.sumCostSince(Instant.EPOCH), List.copyOf(daily), store.topModelsSince(since, TOP_MODELS));
    }
}
