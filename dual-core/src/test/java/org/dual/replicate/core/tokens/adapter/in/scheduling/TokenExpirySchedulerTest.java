package org.dual.replicate.core.tokens.adapter.in.scheduling;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.kernel.EventSource;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenExpirySchedulerTest {

    private final IApiTokens tokens = mock(IApiTokens.class);
    private final ISystemEvents events = mock(ISystemEvents.class);
    private final TokenExpiryScheduler service = new TokenExpiryScheduler(tokens, events);

    @Test
    void sweepRunsTheExpiryCheck() {
        service.sweep();

        verify(tokens).checkExpiries();
        verify(events, never()).record(any(EventSource.class), any(), any(Throwable.class));
    }

    @Test
    void aFailingCheckIsRecordedAndNeverEscapesTheScheduler() {
        RuntimeException failure = new IllegalStateException("db giu'");
        when(tokens.checkExpiries()).thenThrow(failure);

        service.sweep();

        verify(events).record(eq(CoreEventSource.TOKENS), eq("tokenExpiryCheck"), eq(failure));
    }
}
