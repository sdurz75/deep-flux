package org.dual.replicate.core.push.application;

import org.dual.replicate.core.push.domain.PushMessage;
import org.dual.replicate.core.push.port.in.IClientPush;
import org.dual.replicate.core.push.port.in.IClientPushStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Sorgente delle notifiche push verso le tab connesse a {@code GET /events}: un {@link Sinks.Many}, non un registro di
 * {@code SseEmitter} scritto a mano (stesso tipo Reactor che gia' gira sotto il cofano per il client HTTP verso gli LLM, vedi
 * CLAUDE.md, eccezione WebFlux). Il controller resta un {@code @RestController} Spring MVC ordinario.
 * <p>
 * {@code directBestEffort()}, non {@code onBackpressureBuffer()} sul sink condiviso: quest'ultimo avrebbe {@code autoCancel}
 * true di default (il sink si chiuderebbe alla disconnessione dell'ultima tab) e bufferizzerebbe gli eventi emessi mentre
 * nessuno e' connesso, replicandoli a chi si connette dopo (un evento vecchio ricomparirebbe duplicato). Il buffering vive
 * invece per-sottoscrittore in {@link #subscribe()}: ReactiveTypeHandler di Spring MVC richiede un elemento alla volta
 * ({@code request(1)}, poi un altro solo dopo aver spedito il precedente), e senza buffer un secondo evento emesso mentre
 * il primo e' in volo verrebbe scartato da {@code directBestEffort}.
 * <p>
 * Broadcast a tutti i sottoscrittori (app single-user): nessuno scoping; eventuali filtri avvengono lato client.
 */
@Service
public class PushService implements IClientPush, IClientPushStream {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final Sinks.Many<PushMessage> sink = Sinks.many().multicast().directBestEffort();

    @Override
    public Flux<PushMessage> subscribe() {
        return sink.asFlux().onBackpressureBuffer();
    }

    /**
     * synchronized: gli emit arrivano da thread @Async e da thread Tomcat, e {@code tryEmitNext} concorrente su un
     * {@code Sinks.Many} fallisce con FAIL_NON_SERIALIZED (Reactor richiede emissioni serializzate), scartando l'evento. Il
     * volume e' basso, un lock esclusivo non e' un problema.
     */
    @Override
    public synchronized void emit(String eventName, Object data) {
        Sinks.EmitResult result = sink.tryEmitNext(new PushMessage(eventName, data));
        if (result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            // Atteso: nessuna tab connessa. L'evento di dominio e' comunque persistito, solo il push live si perde.
            log.debug("Evento SSE \"{}\" non consegnato: nessuna tab connessa.", eventName);
        } else if (result.isFailure()) {
            // Qualunque altro fallimento e' un evento perso non spiegato da "nessuno collegato": da tracciare.
            log.warn("Evento SSE \"{}\" perso: {}", eventName, result);
        }
    }
}
