package org.dual.hexa.ai.credits.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.dual.hexa.ai.llm.domain.OpenRouterException;
import org.dual.hexa.ai.credits.domain.CreditLine;
import org.dual.hexa.ai.credits.port.out.IOpenRouterCreditGateway;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterCreditSourceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final IOpenRouterCreditGateway openRouter = mock(IOpenRouterCreditGateway.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private MutableClock clock;
    private OpenRouterCreditSource source;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        when(openRouter.isConfigured()).thenReturn(true);
        source = new OpenRouterCreditSource(openRouter, systemEvents, clock);
    }

    @Test
    void isOmittedWithoutAManagementKey() {
        when(openRouter.isConfigured()).thenReturn(false);

        assertThat(source.lines()).isEmpty();
        verify(openRouter, never()).remaining();
    }

    @Test
    void isCachedForTheTtl() {
        when(openRouter.remaining()).thenReturn(new BigDecimal("42.5"));

        source.lines();
        clock.advance(OpenRouterCreditSource.TTL.minusSeconds(1));
        CreditLine cached = source.lines().get(0);
        verify(openRouter, times(1)).remaining();
        assertThat(cached.amountUsd()).isEqualByComparingTo("42.5");
        assertThat(cached.estimated()).isFalse();

        clock.advance(java.time.Duration.ofSeconds(2));
        source.lines();
        verify(openRouter, times(2)).remaining();
    }

    @Test
    void aFailureIsRecordedOnceAndRememberedBrieflyWithoutBreakingTheBar() {
        when(openRouter.remaining()).thenThrow(new OpenRouterException("down", null, Kind.TRANSIENT));

        CreditLine first = source.lines().get(0);
        CreditLine again = source.lines().get(0);

        assertThat(first.status()).isEqualTo(CreditLine.Status.UNAVAILABLE);
        assertThat(again).isEqualTo(first);
        verify(systemEvents, times(1)).record(anyString(), any(Throwable.class));
        verify(openRouter, times(1)).remaining();

        clock.advance(OpenRouterCreditSource.FAILURE_TTL.plusSeconds(1));
        source.lines();
        verify(openRouter, times(2)).remaining();
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
