package org.hexa.core.push.adapter.in.web;

import org.hexa.core.push.port.in.IClientPushStream;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/**
 * Unico endpoint SSE dell'app: le pagine vi si connettono (vedi fragments/core/live-events.html) per ricevere i push invece di
 * fare polling. La sorgente Reactor (Flux/Sinks) e' {@code PushService}.
 * <p>
 * Il tipo di ritorno Flux non fa di questo un endpoint WebFlux: resta un {@code @RestController} Spring MVC ordinario su
 * Tomcat/servlet, perche' spring-webmvc supporta nativamente i tipi reattivi come tipo di ritorno ({@code ReactiveTypeHandler}),
 * riconoscendo {@code Flux<ServerSentEvent<?>>} e trasmettendolo come SSE senza spring-boot-starter-webflux (vedi CLAUDE.md).
 */
@RestController
public class EventStreamController {

    private final IClientPushStream stream;

    public EventStreamController(IClientPushStream stream) {
        this.stream = stream;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events() {
        return stream.subscribe().map(message -> ServerSentEvent.builder(message.data()).event(message.event()).build());
    }
}
