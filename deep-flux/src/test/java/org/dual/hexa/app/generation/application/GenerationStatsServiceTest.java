package org.dual.hexa.app.generation.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.dual.hexa.app.generation.domain.ActivityRow;
import org.dual.hexa.app.generation.domain.DailyActivity;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationStats;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.StatusCount;
import org.dual.hexa.app.generation.port.out.IGenerationStatsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationStatsServiceTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    /** 2026-10-08 12:00 a Roma. */
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private final IGenerationStatsStore store = mock(IGenerationStatsStore.class);
    private GenerationStatsService service;

    @BeforeEach
    void setUp() {
        when(store.findActivitySince(any())).thenReturn(List.of());
        when(store.countByStatus()).thenReturn(List.of());
        when(store.topModelsSince(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(store.sumCostSince(any())).thenReturn(BigDecimal.ZERO);
        service = new GenerationStatsService(store, Clock.fixed(NOW, ROME));
    }

    @Test
    void windowHasOneEntryPerDayEndingToday_evenWithoutActivity() {
        GenerationStats stats = service.stats(30);

        assertThat(stats.daily()).hasSize(30);
        assertThat(stats.daily().get(29).day()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(stats.daily().get(0).day()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(stats.daily()).allMatch(d -> d.total() == 0);
        assertThat(stats.launchedInWindow()).isZero();
        assertThat(stats.successPercent()).isEqualTo(-1);
    }

    @Test
    void groupsByDayInTheClockZone_notInUtc() {
        // 23:30 UTC del 7 = 01:30 dell'8 a Roma: conta come 8 ottobre.
        Instant lateUtc = Instant.parse("2026-10-07T23:30:00Z");
        Instant yesterdayNoon = Instant.parse("2026-10-07T10:00:00Z");
        when(store.findActivitySince(any())).thenReturn(List.of(
                new ActivityRow(lateUtc, GenerationStatus.SUCCEEDED, new BigDecimal("0.04")),
                new ActivityRow(NOW, GenerationStatus.FAILED, null),
                new ActivityRow(yesterdayNoon, GenerationStatus.SUCCEEDED, new BigDecimal("0.10"))));

        GenerationStats stats = service.stats(7);

        DailyActivity today = stats.daily().get(6);
        DailyActivity yesterday = stats.daily().get(5);
        assertThat(today.succeeded()).isEqualTo(1);
        assertThat(today.failed()).isEqualTo(1);
        assertThat(today.costUsd()).isEqualByComparingTo("0.04");
        assertThat(yesterday.succeeded()).isEqualTo(1);
        assertThat(yesterday.costUsd()).isEqualByComparingTo("0.10");
        assertThat(stats.costWindow()).isEqualByComparingTo("0.14");
    }

    @Test
    void costLast7CoversTodayAndTheSixPreviousDaysOnly() {
        when(store.findActivitySince(any())).thenReturn(List.of(
                new ActivityRow(Instant.parse("2026-10-01T10:00:00Z"), GenerationStatus.SUCCEEDED, new BigDecimal("1.00")), // 7 giorni fa: fuori
                new ActivityRow(Instant.parse("2026-10-02T10:00:00Z"), GenerationStatus.SUCCEEDED, new BigDecimal("2.00")))); // 6 giorni fa: dentro

        GenerationStats stats = service.stats(30);

        assertThat(stats.costLast7()).isEqualByComparingTo("2.00");
        assertThat(stats.costWindow()).isEqualByComparingTo("3.00");
    }

    @Test
    void successPercentIsComputedOnFinishedGenerationsAndInProgressIsSeparate() {
        when(store.countSucceeded(GenerationKind.IMAGE)).thenReturn(7L);
        when(store.countSucceeded(GenerationKind.VIDEO)).thenReturn(1L);
        when(store.countByStatus()).thenReturn(List.of(new StatusCount(GenerationStatus.SUCCEEDED, 8),
                new StatusCount(GenerationStatus.FAILED, 2), new StatusCount(GenerationStatus.PROCESSING, 3),
                new StatusCount(GenerationStatus.PENDING, 1)));

        GenerationStats stats = service.stats(30);

        assertThat(stats.failed()).isEqualTo(2);
        assertThat(stats.inProgress()).isEqualTo(4);
        assertThat(stats.finished()).isEqualTo(10);
        assertThat(stats.successPercent()).isEqualTo(80);
    }

    @Test
    void rejectsAWindowOutOfRange() {
        assertThatThrownBy(() -> service.stats(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.stats(367)).isInstanceOf(IllegalArgumentException.class);
    }
}
