package org.dual.replicate.app.credits.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.dual.replicate.app.credits.domain.CreditLine;
import org.dual.replicate.app.credits.domain.CreditProvider;
import org.dual.replicate.app.credits.domain.CreditsException;
import org.dual.replicate.app.credits.domain.ReplicateBalanceAnchor;
import org.dual.replicate.app.credits.port.out.IOpenRouterCreditGateway;
import org.dual.replicate.app.credits.port.out.IReplicateBalanceStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.ai.domain.OpenRouterException;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditsServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final IReplicateBalanceStore balanceStore = mock(IReplicateBalanceStore.class);
    private final IOpenRouterCreditGateway openRouter = mock(IOpenRouterCreditGateway.class);
    private final IGenerations generations = mock(IGenerations.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final Messages messages = mock(Messages.class);
    private MutableClock clock;
    private CreditsService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        when(openRouter.isConfigured()).thenReturn(true);
        when(balanceStore.find()).thenReturn(Optional.empty());
        service = new CreditsService(balanceStore, openRouter, generations, systemEvents, messages, clock);
    }

    @Test
    void replicateWithoutABalanceAsksForOne() {
        assertThat(service.lines().get(0)).isEqualTo(CreditLine.notSet(CreditProvider.REPLICATE));
        verify(generations, never()).totalCostSince(any());
    }

    @Test
    void replicateIsTheEnteredBalanceMinusTheCostSinceThen() {
        Instant asOf = T0.minusSeconds(3600);
        when(balanceStore.find()).thenReturn(Optional.of(new ReplicateBalanceAnchor(new BigDecimal("10.0000"), asOf)));
        when(generations.totalCostSince(asOf)).thenReturn(new BigDecimal("3.25"));

        CreditLine line = service.lines().get(0);

        assertThat(line.status()).isEqualTo(CreditLine.Status.OK);
        assertThat(line.amountUsd()).isEqualByComparingTo("6.75");
        assertThat(line.estimated()).isTrue();
        assertThat(line.low()).isFalse();
    }

    @Test
    void replicateNeverGoesBelowZeroAndIsLowUnderTheThreshold() {
        Instant asOf = T0.minusSeconds(60);
        when(balanceStore.find()).thenReturn(Optional.of(new ReplicateBalanceAnchor(new BigDecimal("1"), asOf)));
        when(generations.totalCostSince(asOf)).thenReturn(new BigDecimal("5"));

        CreditLine line = service.lines().get(0);

        assertThat(line.amountUsd()).isEqualByComparingTo("0");
        assertThat(line.low()).isTrue();
    }

    @Test
    void openRouterIsOmittedWithoutAManagementKey() {
        when(openRouter.isConfigured()).thenReturn(false);

        assertThat(service.lines()).extracting(CreditLine::provider).containsExactly(CreditProvider.REPLICATE);
        verify(openRouter, never()).remaining();
    }

    @Test
    void openRouterIsCachedForTheTtl() {
        when(openRouter.remaining()).thenReturn(new BigDecimal("42.5"));

        service.lines();
        clock.advance(CreditsService.TTL.minusSeconds(1));
        CreditLine cached = service.lines().get(1);
        verify(openRouter, times(1)).remaining();
        assertThat(cached.amountUsd()).isEqualByComparingTo("42.5");

        clock.advance(java.time.Duration.ofSeconds(2));
        service.lines();
        verify(openRouter, times(2)).remaining();
    }

    @Test
    void anOpenRouterFailureIsRecordedOnceAndRememberedBrieflyWithoutBreakingTheBar() {
        when(openRouter.remaining()).thenThrow(new OpenRouterException("down", null, Kind.TRANSIENT));

        CreditLine first = service.lines().get(1);
        CreditLine again = service.lines().get(1);

        assertThat(first.status()).isEqualTo(CreditLine.Status.UNAVAILABLE);
        assertThat(again).isEqualTo(first);
        verify(systemEvents, times(1)).record(anyString(), any(Throwable.class));
        verify(openRouter, times(1)).remaining();

        clock.advance(CreditsService.FAILURE_TTL.plusSeconds(1));
        service.lines();
        verify(openRouter, times(2)).remaining();
    }

    @Test
    void settingTheBalanceStoresItWithTheCurrentInstant() {
        service.setReplicateBalance(new BigDecimal("25.5"));

        ArgumentCaptor<ReplicateBalanceAnchor> saved = ArgumentCaptor.forClass(ReplicateBalanceAnchor.class);
        verify(balanceStore).save(saved.capture());
        assertThat(saved.getValue().getBalanceUsd()).isEqualByComparingTo("25.5");
        assertThat(saved.getValue().getAsOf()).isEqualTo(T0);
    }

    @Test
    void anInvalidBalanceIsAnExpectedRejection() {
        for (BigDecimal bad : new BigDecimal[] {null, new BigDecimal("-1"), new BigDecimal("1000000000")}) {
            assertThatThrownBy(() -> service.setReplicateBalance(bad)).isInstanceOfSatisfying(CreditsException.class,
                    e -> assertThat(e.isReportable()).isFalse());
        }
        verify(balanceStore, never()).save(any());
    }

    /** Orologio spostabile a mano. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
