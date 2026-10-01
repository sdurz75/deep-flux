package org.dual.replicate.app.generation.adapter.out.push;

import org.dual.replicate.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.replicate.core.push.port.in.IClientPush;
import org.dual.replicate.app.generation.domain.event.GenerationImageDeletedEvent;
import org.dual.replicate.app.generation.domain.event.GenerationsDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Il lato generation del push verso /gallery e /deep-chat: traduce gli eventi di dominio delle generazioni nell'evento SSE
 * {@code gallery-update} (generico: "qualcosa e' cambiato, ricarica") e lo consegna a {@link IClientPush} (core.push), che lo manda a
 * tutte le tab connesse a GET /events. Il trasporto (Sinks, buffer per sottoscrittore) e' di core.push.
 */
@Component
public class GalleryPushNotifier {

    static final String GALLERY_UPDATE = "gallery-update";

    private final IClientPush push;

    public GalleryPushNotifier(IClientPush push) {
        this.push = push;
    }

    /**
     * Ascolta GenerationCompletedEvent (pubblicato da GenerationService.saveAndLogIfTerminal): copre sia il polling
     * client-side di /generations/{id} sia il watch in background di /deep-chat (ChatGenerationWatcher), un solo
     * punto d'aggancio per qualunque generazione completata, indipendentemente da come e' stata avviata.
     */
    @EventListener
    public void onGenerationCompleted(GenerationCompletedEvent event) {
        push.emit(GALLERY_UPDATE, "refresh");
    }

    /**
     * Ascolta GenerationsDeletedEvent (GenerationService#delete/#deleteAll): "gallery-update" non porta un payload
     * informativo, e' solo "qualcosa e' cambiato, ricarica": chi ascolta ri-fa fetch della propria vista (galleria globale
     * o contestuale).
     */
    @EventListener
    public void onGenerationsDeleted(GenerationsDeletedEvent event) {
        push.emit(GALLERY_UPDATE, "refresh");
    }

    /** Ascolta GenerationImageDeletedEvent (GenerationService#deleteImage, caso NON a cascata): stesso evento generico. */
    @EventListener
    public void onGenerationImageDeleted(GenerationImageDeletedEvent event) {
        push.emit(GALLERY_UPDATE, "refresh");
    }
}
