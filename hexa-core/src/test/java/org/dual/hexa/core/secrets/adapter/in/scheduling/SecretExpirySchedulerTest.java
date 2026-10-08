package org.dual.hexa.core.secrets.adapter.in.scheduling;

import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.EventSource;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecretExpirySchedulerTest {

    private final ISecrets secrets = mock(ISecrets.class);
    private final ISystemEvents events = mock(ISystemEvents.class);
    private final SecretExpiryScheduler service = new SecretExpiryScheduler(secrets, events);

    @Test
    void sweepRunsTheExpiryCheck() {
        service.sweep();

        verify(secrets).checkExpiries();
        verify(events, never()).record(any(EventSource.class), any(), any(Throwable.class));
    }

    @Test
    void aFailingCheckIsRecordedAndNeverEscapesTheScheduler() {
        RuntimeException failure = new IllegalStateException("db giu'");
        when(secrets.checkExpiries()).thenThrow(failure);

        service.sweep();

        verify(events).record(eq(CoreEventSource.SECRETS), eq("secretExpiryCheck"), eq(failure));
    }
}
