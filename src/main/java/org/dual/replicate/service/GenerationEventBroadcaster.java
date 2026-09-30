package org.dual.replicate.service;

import org.dual.replicate.domain.event.ChatMessagePushEvent;
import org.dual.replicate.domain.event.ErrorToastEvent;
import org.dual.replicate.domain.event.GenerationImageDeletedEvent;
import org.dual.replicate.domain.event.GenerationsDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Sorgente delle notifiche push verso /gallery e /deep-chat, un evento
 * SSE per tab/browser connesso a GET /events (vedi EventStreamController).
 * Un {@link Sinks.Many}, non piu' un registro di {@code SseEmitter}: lo
 * stesso tipo Reactor (Flux/Sinks) che gia' gira sotto il cofano per il
 * client HTTP verso OpenRouter (vedi CLAUDE.md, eccezione WebFlux), qui
 * usato per il broadcaster invece che riscritto a mano con liste ed
 * emitter — il controller resta pero' un {@code @RestController} Spring
 * MVC ordinario: {@code Flux} come tipo di ritorno e' supportato
 * nativamente da spring-webmvc (ReactiveTypeHandler) dalla 5.0, nessun
 * server reattivo secondario, nessuna dipendenza spring-webflux.
 * {@code directBestEffort()}, non {@code onBackpressureBuffer()} sul
 * sink condiviso: quest'ultimo avrebbe {@code autoCancel} true di
 * default (il sink si chiuderebbe alla disconnessione dell'ultima tab,
 * niente piu' eventi per le connessioni successive) e bufferizzerebbe
 * gli eventi emessi mentre nessuno e' connesso, replicandoli a chi si
 * connette dopo — un "chat-message" vecchio ricomparirebbe duplicato in
 * una tab che l'ha gia' visto tramite la cronologia persistita. Il
 * buffering per-sottoscrittore vive invece su {@link #subscribe()}
 * sotto ({@code onBackpressureBuffer()} sul Flux della singola tab, non
 * sul sink condiviso): ReactiveTypeHandler di Spring MVC richiede un
 * elemento alla volta (`request(1)`, poi ne richiede un altro solo dopo
 * aver spedito il precedente) — senza questo buffer, un secondo evento
 * emesso mentre il primo e' ancora in volo (es. "gallery-update" e poi,
 * pochi ms dopo, "chat-message" dallo stesso DeepChatGenerationWatcher)
 * verrebbe silenziosamente scartato da {@code directBestEffort} per
 * mancanza di richiesta, non solo per assenza di sottoscrittori.
 * App non multi-utente (vedi CLAUDE.md): broadcast a tutti i
 * sottoscrittori connessi, nessuno scoping/autenticazione per
 * conversazione — il filtro per conversazione (evento "chat-message")
 * avviene lato client, confrontando conversationId con quella
 * attualmente aperta.
 */
@Component
public class GenerationEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(GenerationEventBroadcaster.class);

    private final Sinks.Many<ServerSentEvent<Object>> sink = Sinks.many().multicast().directBestEffort();

    public Flux<ServerSentEvent<Object>> subscribe() {
        return sink.asFlux().onBackpressureBuffer();
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
        emit("gallery-update", "refresh");
    }

    /**
     * Ascolta GenerationsDeletedEvent (pubblicato da GenerationService#delete/
     * #deleteAll): stesso evento SSE del completamento, "gallery-update" non
     * porta un payload informativo, e' solo "qualcosa e' cambiato, ricarica"
     * — chi ascolta non ha bisogno di sapere quali id sono spariti, solo di
     * ri-fare fetch della propria vista (galleria globale o contestuale).
     */
    @EventListener
    public void onGenerationsDeleted(GenerationsDeletedEvent event) {
        emit("gallery-update", "refresh");
    }

    /**
     * Ascolta GenerationImageDeletedEvent (pubblicato da GenerationService#deleteImage,
     * caso NON a cascata: l'immagine sparisce ma la generazione resta) - stesso evento SSE
     * generico degli altri due sopra, chi ascolta ri-fa semplicemente fetch della propria vista.
     */
    @EventListener
    public void onGenerationImageDeleted(GenerationImageDeletedEvent event) {
        emit("gallery-update", "refresh");
    }

    /**
     * Toast d'errore (vedi AppErrorService): pubblicato come ErrorToastEvent, consegnato a
     * tutte le tab connesse a /events (anche se l'errore nasce in un thread @Async).
     */
    @EventListener
    public void onErrorToast(ErrorToastEvent event) {
        emit("error-toast", event);
    }

    public void broadcastChatMessage(ChatMessagePushEvent payload) {
        emit("chat-message", payload);
    }

    /**
     * synchronized: gli emit arrivano sia da thread @Async (DeepChatGenerationWatcher)
     * sia da thread Tomcat (il polling di /generations/{id} passa dallo
     * stesso GenerationCompletedEvent) — {@code tryEmitNext} concorrente
     * su un {@code Sinks.Many} fallisce con FAIL_NON_SERIALIZED (Reactor
     * richiede emissioni serializzate), scartando l'evento; qui il volume
     * e' basso (poche generazioni alla volta per modello,
     * MAX_IN_PROGRESS_PREDICTIONS_PER_MODEL in GenerationService), un
     * lock esclusivo non e' un problema di performance.
     */
    private synchronized void emit(String eventName, Object data) {
        ServerSentEvent<Object> event = ServerSentEvent.builder(data).event(eventName).build();
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
            // Atteso: nessuna tab connessa in questo momento. Non e' un
            // errore applicativo, il messaggio resta comunque persistito
            // (vedi DeepChatGenerationWatcher/GenerationService), solo il
            // push live va perso — coerente con la nota sul riconnect di
            // EventSource in README.md.
            log.debug("Evento SSE \"{}\" non consegnato: nessuna tab connessa.", eventName);
        } else if (result.isFailure()) {
            // Qualunque altro fallimento (es. FAIL_NON_SERIALIZED se lo
            // synchronized sopra venisse mai rimosso) e' invece un evento
            // perso non spiegato da "nessuno collegato": da tracciare.
            log.warn("Evento SSE \"{}\" perso: {}", eventName, result);
        }
    }

}
