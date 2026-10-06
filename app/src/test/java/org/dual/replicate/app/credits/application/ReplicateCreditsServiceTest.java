package org.dual.replicate.app.credits.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.dual.replicate.app.credits.domain.CreditsException;
import org.dual.replicate.app.credits.domain.ReplicateBalanceAnchor;
import org.dual.replicate.app.credits.port.out.IReplicateBalanceStore;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.credits.domain.CreditLine;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplicateCreditsServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final IReplicateBalanceStore balanceStore = mock(IReplicateBalanceStore.class);
    private final IGenerations generations = mock(IGenerations.class);
    private final Messages messages = mock(Messages.class);
    private ReplicateCreditsService service;

    @BeforeEach
    void setUp() {
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        when(balanceStore.find()).thenReturn(Optional.empty());
        service = new ReplicateCreditsService(balanceStore, generations, messages, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void withoutABalanceAsksForOne() {
        assertThat(service.lines()).containsExactly(CreditLine.notSet("REPLICATE", true));
        verify(generations, never()).totalCostSince(any());
    }

    @Test
    void isTheEnteredBalanceMinusTheCostSinceThen() {
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
    void neverGoesBelowZeroAndIsLowUnderTheThreshold() {
        Instant asOf = T0.minusSeconds(60);
        when(balanceStore.find()).thenReturn(Optional.of(new ReplicateBalanceAnchor(new BigDecimal("1"), asOf)));
        when(generations.totalCostSince(asOf)).thenReturn(new BigDecimal("5"));

        CreditLine line = service.lines().get(0);

        assertThat(line.amountUsd()).isEqualByComparingTo("0");
        assertThat(line.low()).isTrue();
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
}
