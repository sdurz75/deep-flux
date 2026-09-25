package org.dual.replicate.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registro degli emitter SSE (uno per tab/browser connesso a
 * GET /events, vedi EventStreamController) e punto unico da cui
 * partono le notifiche push verso /gallery e /deep-chat. App non
 * multi-utente (vedi CLAUDE.md): broadcast a tutti gli emitter connessi,
 * nessuno scoping/autenticazione per conversazione — il filtro per
 * conversazione (evento "chat-message") avviene lato client, confrontando
 * conversationId con quella attualmente aperta.
 */
@Component
public class GenerationEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(GenerationEventBroadcaster.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        Runnable cleanup = () -> emitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    /**
     * Ascolta GenerationCompletedEvent (pubblicato da
     * GenerationService.saveAndLogIfTerminal): copre sia il polling
     * client-side di /generations/{id} sia il watch in background di
     * /deep-chat (DeepChatGenerationWatcher), un solo punto d'aggancio
     * per qualunque generazione completata, indipendentemente da come
     * e' stata avviata.
     */
    @EventListener
    public void onGenerationCompleted(GenerationCompletedEvent event) {
        broadcast("gallery-update", "refresh");
    }

    public void broadcastChatMessage(ChatMessagePush payload) {
        broadcast("chat-message", payload);
    }

    private void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                log.debug("Emitter SSE non piu' valido, rimosso dal registro: {}", e.getMessage());
                emitter.complete();
                emitters.remove(emitter);
            }
        }
    }

    /** Payload dell'evento "chat-message": vocabolario JSON di deep-chat, vedi DeepChatService.FileRef. */
    public record ChatMessagePush(Long conversationId, String text, List<DeepChatService.FileRef> files) {
    }
}
