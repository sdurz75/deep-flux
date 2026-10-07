package org.dual.hexa.app.training.adapter.out.push;

import org.dual.hexa.app.training.domain.event.TrainingChangedEvent;
import org.dual.hexa.core.push.port.in.IClientPush;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Un training che cambia diventa l'evento SSE generico {@code training-update}; il trasporto e' di core.push. */
class TrainingPushNotifierTest {

    private final IClientPush push = mock(IClientPush.class);
    private final TrainingPushNotifier notifier = new TrainingPushNotifier(push);

    @Test
    void aChangedTrainingEmitsTrainingUpdate() {
        notifier.onTrainingChanged(new TrainingChangedEvent(7L));

        verify(push).emit("training-update", "refresh");
        verifyNoMoreInteractions(push);
    }
}
