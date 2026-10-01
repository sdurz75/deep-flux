package org.dual.replicate.service;

import org.dual.replicate.domain.SystemEventSource;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTokenExpiryServiceTest {

    private final ApiTokenService tokens = mock(ApiTokenService.class);
    private final SystemEventService events = mock(SystemEventService.class);
    private final ApiTokenExpiryService service = new ApiTokenExpiryService(tokens, events);

    @Test
    void sweepRunsTheExpiryCheck() {
        service.sweep();

        verify(tokens).checkExpiries();
        verify(events, never()).record(any(SystemEventSource.class), any(), any(Throwable.class));
    }

    @Test
    void aFailingCheckIsRecordedAndNeverEscapesTheScheduler() {
        RuntimeException failure = new IllegalStateException("db giu'");
        when(tokens.checkExpiries()).thenThrow(failure);

        service.sweep();

        verify(events).record(eq(SystemEventSource.TOKENS), eq("tokenExpiryCheck"), eq(failure));
    }
}
