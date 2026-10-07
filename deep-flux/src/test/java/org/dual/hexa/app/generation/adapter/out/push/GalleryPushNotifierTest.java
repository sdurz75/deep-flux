package org.dual.hexa.app.generation.adapter.out.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;

import org.dual.hexa.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.hexa.core.push.port.in.IClientPush;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.event.GenerationImageDeletedEvent;
import org.dual.hexa.app.generation.domain.event.GenerationsDeletedEvent;
import org.junit.jupiter.api.Test;

/** Gli eventi di dominio delle generazioni diventano i due eventi SSE dell'app; il trasporto e' di core.push (PushServiceTest). */
class GalleryPushNotifierTest {

    private final IClientPush push = mock(IClientPush.class);
    private final GalleryPushNotifier broadcaster = new GalleryPushNotifier(push);

    @Test
    void completedGenerationEmitsGalleryUpdate() {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);

        broadcaster.onGenerationCompleted(new GenerationCompletedEvent(generation));

        verify(push).emit("gallery-update", "refresh");
        verifyNoMoreInteractions(push);
    }

    /**
     * Una cancellazione (singola o in blocco, vedi IGenerations #delete/#deleteAll) riusa lo stesso evento SSE del
     * completamento: "gallery-update" e' generico ("qualcosa e' cambiato, ricarica"), sia la galleria globale sia quella
     * contestuale di /deep-chat lo ascoltano gia' per questo motivo.
     */
    @Test
    void generationsDeletedEventEmitsGalleryUpdate() {
        broadcaster.onGenerationsDeleted(new GenerationsDeletedEvent(List.of(1L, 2L)));

        verify(push).emit("gallery-update", "refresh");
    }

    /** Cancellazione per-immagine NON a cascata (GenerationService#deleteImage): stesso evento generico, non un tipo diverso. */
    @Test
    void generationImageDeletedEventEmitsGalleryUpdate() {
        broadcaster.onGenerationImageDeleted(new GenerationImageDeletedEvent(1L));

        verify(push).emit("gallery-update", "refresh");
    }
}
