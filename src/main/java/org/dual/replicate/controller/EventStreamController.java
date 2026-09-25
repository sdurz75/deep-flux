package org.dual.replicate.controller;

import org.dual.replicate.service.GenerationEventBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unico endpoint SSE dell'app: /gallery e /deep-chat vi si connettono
 * (vedi fragments/live-events.html) per ricevere il push del
 * completamento di una generazione, invece di fare polling. Vedi
 * GenerationEventBroadcaster per il registro degli emitter e gli eventi
 * emessi ("gallery-update", "chat-message").
 */
@RestController
public class EventStreamController {

    private final GenerationEventBroadcaster broadcaster;

    public EventStreamController(GenerationEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return broadcaster.subscribe();
    }
}
