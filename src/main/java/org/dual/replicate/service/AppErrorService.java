package org.dual.replicate.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.domain.AppError;
import org.dual.replicate.domain.AppErrorSource;
import org.dual.replicate.domain.event.ErrorToastEvent;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.remote.RemoteServiceException;
import org.dual.replicate.repository.AppErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Unico punto di registrazione degli errori delle chiamate remote e degli
 * errori interni non gestiti (vedi CLAUDE.md, "Errori e stati terminali"):
 * ogni {@code catch} che riguarda Replicate/OpenRouter/SearXNG/storage
 * passa da qui invece di ingoiare l'eccezione o loggarla a mano.
 * <p>
 * {@link #record} fa tre cose: (1) logga con stack, (2) salva/aggiorna una
 * riga {@link AppError} (transazione propria, REQUIRES_NEW: sopravvive al
 * rollback del chiamante), (3) se e' la prima occorrenza di una serie,
 * pubblica un {@link ErrorToastEvent} (→ SSE "error-toast"). NON lancia
 * mai: un fallimento del registro stesso viene solo loggato, altrimenti
 * il codice di gestione errori creerebbe nuovi errori.
 * <p>
 * Serie: lo stesso errore (source+operation+generationId+tipo di
 * eccezione) ripetuto entro {@link #SERIES_WINDOW} aggiorna la riga
 * esistente (occurrences++) senza nuovo toast: il polling di
 * /generations/{id} ogni 2s durante un'outage non deve produrre centinaia
 * di righe/toast.
 * <p>
 * Locale: il messaggio del toast e' risolto con la locale del thread
 * corrente (vedi i18n/Messages); i thread async che vogliono la locale
 * della richiesta d'origine la impostano prima (vedi DeepChatGenerationWatcher).
 */
@Service
public class AppErrorService {

    private static final Logger log = LoggerFactory.getLogger(AppErrorService.class);

    static final Duration SERIES_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_TOAST = 200;
    private static final int MAX_DETAILS = 8000;

    /** Esito di {@link #record}: {@code key} per la dedupe lato client, {@code firstOfSeries} se ha generato un toast SSE. */
    public record Recorded(String key, String message, boolean firstOfSeries, boolean transientFailure) {
    }

    private final AppErrorRepository repository;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher eventPublisher;
    private final Messages messages;
    private final ObjectMapper objectMapper;

    public AppErrorService(AppErrorRepository repository, PlatformTransactionManager transactionManager,
                            ApplicationEventPublisher eventPublisher, Messages messages, ObjectMapper objectMapper) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.eventPublisher = eventPublisher;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    /**
     * Come {@link #record(AppErrorSource, String, Throwable)}, con la source ricavata dall'eccezione
     * ({@link RemoteServiceException#source()}, anche se incapsulata; {@code INTERNAL} per il resto): il chiamante non
     * deve conoscere il servizio da cui viene l'errore.
     */
    public Recorded record(String operation, Throwable error) {
        return record(sourceOf(error), operation, error, null, null);
    }

    public Recorded record(String operation, Throwable error, Long generationId, Long conversationId) {
        return record(sourceOf(error), operation, error, generationId, conversationId);
    }

    /** {@link #record(String, Throwable, Long, Long)} + toast nella risposta htmx (il boilerplate dei controller). */
    public Recorded recordForHtmx(HttpServletResponse response, String operation, Throwable error) {
        return recordForHtmx(response, operation, error, null, null);
    }

    public Recorded recordForHtmx(HttpServletResponse response, String operation, Throwable error, Long generationId, Long conversationId) {
        Recorded recorded = record(operation, error, generationId, conversationId);
        addToastHeader(response, recorded);
        return recorded;
    }

    /** La source dell'eccezione remota (anche nella catena delle cause), {@code INTERNAL} se non lo e'. */
    public static AppErrorSource sourceOf(Throwable error) {
        RemoteServiceException remote = remoteCause(error);
        return remote != null ? remote.source() : AppErrorSource.INTERNAL;
    }

    private static RemoteServiceException remoteCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RemoteServiceException remote) {
                return remote;
            }
        }
        return null;
    }

    public Recorded record(AppErrorSource source, String operation, Throwable error) {
        return record(source, operation, error, null, null);
    }

    public Recorded record(AppErrorSource source, String operation, Throwable error, Long generationId, Long conversationId) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        boolean transientFailure = remoteCause(error) != null && remoteCause(error).isTransient();
        String message = sanitize(error);
        log.warn("Errore [{}] {} (generationId={}, conversationId={}): {}", source, operation, generationId, conversationId, message, error);

        String toastMessage = toast(source, message);
        String key = UUID.randomUUID().toString();
        boolean first = true;
        try {
            String details = stack(error);
            Instant now = Instant.now();
            Saved saved = transaction.execute(status -> {
                var open = repository.findFirstBySourceAndOperationAndGenerationIdAndErrorTypeAndLastSeenAtAfterOrderByIdDesc(
                        source, operation, generationId, type, now.minus(SERIES_WINDOW));
                if (open.isPresent()) {
                    AppError existing = open.get();
                    existing.repeat(message, details, now);
                    repository.save(existing);
                    return new Saved(existing.getId(), false);
                }
                AppError created = repository.save(new AppError(source, operation, type, message, details, generationId, conversationId, now));
                return new Saved(created.getId(), true);
            });
            if (saved != null) {
                key = "e" + saved.id() + (saved.first() ? "" : "-" + UUID.randomUUID());
                first = saved.first();
            }
        } catch (RuntimeException registryFailure) {
            // Il registro non deve mai far fallire il chiamante: si perde solo la riga, resta il log sopra.
            log.error("Impossibile salvare l'errore nel registro: {}", registryFailure.toString());
        }
        if (first) {
            try {
                eventPublisher.publishEvent(new ErrorToastEvent(key, toastMessage, transientFailure));
            } catch (RuntimeException publishFailure) {
                log.error("Impossibile pubblicare il toast d'errore: {}", publishFailure.toString());
            }
        }
        return new Recorded(key, toastMessage, first, transientFailure);
    }

    /**
     * Aggiunge alla risposta htmx l'header {@code HX-Trigger} che fa comparire il toast
     * (evento {@code app-error}, vedi fragments/toast.html). Sempre presente, anche per una
     * ripetizione di serie: e' l'utente che ha appena provato un'azione e deve saperne l'esito.
     */
    public void addToastHeader(HttpServletResponse response, Recorded recorded) {
        Map<String, Object> toast = new java.util.LinkedHashMap<>();
        toast.put("key", recorded.key());
        toast.put("message", recorded.message());
        toast.put("transient", recorded.transientFailure());
        addHxTrigger(response, "app-error", toast);
    }

    /**
     * Aggiunge un evento all'header {@code HX-Trigger} SENZA sovrascrivere quelli gia' presenti (un controller puo'
     * emettere {@code gallery-update} e, nello stesso giro, un toast). Il valore esistente puo' essere un elenco di nomi
     * ("a, b") o un oggetto JSON; il risultato e' sempre un unico oggetto JSON {@code {evento: dettaglio}}.
     */
    public void addHxTrigger(HttpServletResponse response, String event, Object detail) {
        try {
            Map<String, Object> events = new java.util.LinkedHashMap<>();
            String existing = response.getHeader("HX-Trigger");
            if (existing != null && !existing.isBlank()) {
                if (existing.trim().startsWith("{")) {
                    events.putAll(objectMapper.readValue(existing, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }));
                } else {
                    for (String name : existing.split(",")) {
                        if (!name.isBlank()) {
                            events.put(name.trim(), "");
                        }
                    }
                }
            }
            events.put(event, detail);
            response.setHeader("HX-Trigger", objectMapper.writeValueAsString(events));
        } catch (RuntimeException e) {
            log.error("Impossibile costruire l'header HX-Trigger ({}): {}", event, e.toString());
        }
    }

    private record Saved(Long id, boolean first) {
    }

    private String toast(AppErrorSource source, String message) {
        String shortMessage = message.length() > MAX_TOAST ? message.substring(0, MAX_TOAST) + "…" : message;
        try {
            return messages.get("toast.error.message", messages.get("errors.source." + source.name()), shortMessage);
        } catch (RuntimeException e) {
            return source.getLabel() + ": " + shortMessage;
        }
    }

    /** Messaggio breve e su una riga, mai null. */
    public static String sanitize(Throwable error) {
        if (error == null) {
            return "Errore sconosciuto";
        }
        String text = error.getMessage();
        if (text == null || text.isBlank()) {
            text = error.getClass().getSimpleName();
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) + "…" : text;
    }

    private static String stack(Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();
        return text.length() > MAX_DETAILS ? text.substring(0, MAX_DETAILS) + "\n…" : text;
    }
}
