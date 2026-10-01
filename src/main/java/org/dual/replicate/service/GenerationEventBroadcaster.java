package org.dual.replicate.service;

import org.dual.replicate.core.push.port.in.IClientPush;
import org.dual.replicate.domain.event.ChatMessagePushEvent;
import org.dual.replicate.domain.event.GenerationImageDeletedEvent;
import org.dual.replicate.domain.event.GenerationsDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Il lato app del push verso /gallery e /deep-chat: traduce gli eventi di dominio delle generazioni nei due eventi SSE
 * dell'app ({@code gallery-update} e {@code chat-message}) e li consegna a {@link IClientPush} (core.push), che li manda a
 * tutte le tab connesse a GET /events. Il trasporto (Sinks, buffer per sottoscrittore) non e' piu' qui.
 * <p>
 * Il filtro per conversazione dell'evento "chat-message" avviene lato client, confrontando conversationId con quella
 * attualmente aperta (app single-user, nessuno scoping).
 */
@Component
public class GenerationEventBroadcaster {

    static final String GALLERY_UPDATE = "gallery-update";
    static final String CHAT_MESSAGE = "chat-message";

    private final IClientPush push;

    public GenerationEventBroadcaster(IClientPush push) {
        this.push = push;
    }

    /**
     * Ascolta GenerationCompletedEvent (pubblicato da GenerationService.saveAndLogIfTerminal): copre sia il polling
     * client-side di /generations/{id} sia il watch in background di /deep-chat (DeepChatGenerationWatcher), un solo
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

    public void broadcastChatMessage(ChatMessagePushEvent payload) {
        push.emit(CHAT_MESSAGE, payload);
    }
}
