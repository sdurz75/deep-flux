package org.dual.replicate.controller;

import org.dual.replicate.service.GenerationEventBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/**
 * Unico endpoint SSE dell'app: /gallery e /deep-chat vi si connettono
 * (vedi fragments/live-events.html) per ricevere il push del
 * completamento di una generazione, invece di fare polling. Vedi
 * GenerationEventBroadcaster per la sorgente Reactor (Flux/Sinks) e gli
 * eventi emessi ("gallery-update", "chat-message").
 *
 * Il tipo di ritorno Flux non fa di questo un endpoint WebFlux: resta
 * un @RestController Spring MVC ordinario su Tomcat/servlet — Spring MVC
 * supporta nativamente i tipi reattivi (Reactor Flux/Mono) come tipo di
 * ritorno dalla 5.0 (ReactiveTypeHandler), riconoscendo Flux<ServerSentEvent<?>>
 * e trasmettendolo come SSE senza bisogno di spring-boot-starter-webflux
 * ne' di un secondo server reattivo (vedi CLAUDE.md).
 */
@RestController
public class EventStreamController {

    private final GenerationEventBroadcaster broadcaster;

    public EventStreamController(GenerationEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events() {
        return broadcaster.subscribe();
    }
}
