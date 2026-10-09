package org.dual.hexa.app.generation.adapter.out.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.hexa.core.events.port.in.INotifications;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Solo una generazione RIUSCITA (non importata) produce il toast, col link al dettaglio. */
class GenerationToastNotifierTest {

    private final INotifications notifications = mock(INotifications.class);
    private final Messages messages = mock(Messages.class);
    private final GenerationToastNotifier notifier = new GenerationToastNotifier(notifications, messages);

    private Generation generation(GenerationStatus status, GenerationKind kind) {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 42L);
        generation.setStatus(status);
        generation.setKind(kind);
        return generation;
    }

    @Test
    void succeededImageNotifiesWithLinkToTheDetail() {
        when(messages.get("generation.toast.doneImage", 42L)).thenReturn("Immagine pronta");

        notifier.onGenerationCompleted(new GenerationCompletedEvent(generation(GenerationStatus.SUCCEEDED, GenerationKind.IMAGE)));

        verify(notifications).success("generation-42", "Immagine pronta", "/generations/42");
    }

    @Test
    void succeededVideoUsesTheVideoText() {
        when(messages.get("generation.toast.doneVideo", 42L)).thenReturn("Video pronto");

        notifier.onGenerationCompleted(new GenerationCompletedEvent(generation(GenerationStatus.SUCCEEDED, GenerationKind.VIDEO)));

        verify(notifications).success("generation-42", "Video pronto", "/generations/42");
    }

    @Test
    void failedGenerationDoesNotNotify() {
        notifier.onGenerationCompleted(new GenerationCompletedEvent(generation(GenerationStatus.FAILED, GenerationKind.IMAGE)));

        verifyNoInteractions(notifications);
    }
}
