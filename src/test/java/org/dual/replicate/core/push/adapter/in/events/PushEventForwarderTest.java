package org.dual.replicate.core.push.adapter.in.events;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.dual.replicate.core.push.port.in.IClientPush;
import org.dual.replicate.domain.event.SystemToastEvent;
import org.junit.jupiter.api.Test;

class PushEventForwarderTest {

    /** Il toast di un evento di sistema arriva a tutte le tab come evento SSE "system-event", col record stesso come payload. */
    @Test
    void aSystemToastEventIsForwardedAsSystemEvent() {
        IClientPush push = mock(IClientPush.class);
        SystemToastEvent toast = new SystemToastEvent("e1", "Errore Replicate: rete giu'");

        new PushEventForwarder(push).on(toast);

        verify(push).emit("system-event", toast);
    }
}
