package org.dual.replicate.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.kernel.EventSource;
import org.dual.replicate.domain.SystemEvent;
import org.dual.replicate.domain.SystemEventSeverity;
import org.dual.replicate.domain.event.SystemToastEvent;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.repository.SystemEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Unico punto di registrazione degli eventi di sistema (vedi CLAUDE.md,
 * "Errori ed eventi di sistema"): gli ERRORI delle chiamate remote e quelli interni non
 * gestiti ({@link #record}: ogni {@code catch} che riguarda Replicate/OpenRouter/SearXNG/
 * storage passa da qui invece di ingoiare l'eccezione o loggarla a mano) e gli AVVISI
 * ({@link #warn}, es. un token in scadenza).
 * <p>
 * {@link #record} fa tre cose: (1) logga con stack, (2) salva/aggiorna una
 * riga {@link SystemEvent} (transazione propria, REQUIRES_NEW: sopravvive al
 * rollback del chiamante), (3) se e' la prima occorrenza di una serie,
 * pubblica un {@link SystemToastEvent} (→ SSE "system-event"). NON lancia
 * mai: un fallimento del registro stesso viene solo loggato, altrimenti
 * il codice di gestione errori creerebbe nuovi errori.
 * <p>
 * Serie: lo stesso evento (severity+source+operation+subject+tipo di
 * eccezione) ripetuto entro la finestra aggiorna la riga esistente (occurrences++) senza
 * nuovo toast: il polling di /generations/{id} ogni 2s durante un'outage non deve produrre
 * centinaia di righe/toast. La finestra e' {@link #SERIES_WINDOW} (5 min) per gli ERRORI e
 * {@code app.events.warning-series-window} (24h) per gli AVVISI: un controllo periodico
 * non deve ripetere lo stesso toast ogni pochi minuti.
 * <p>
 * Locale: il messaggio del toast e' risolto con la locale del thread
 * corrente (vedi i18n/Messages); i thread async che vogliono la locale
 * della richiesta d'origine la impostano prima (vedi DeepChatGenerationWatcher).
 */
@Service
public class SystemEventService {

    private static final Logger log = LoggerFactory.getLogger(SystemEventService.class);

    static final Duration SERIES_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_TOAST = 200;
    private static final int MAX_DETAILS = 8000;

    /** Esito di {@link #record}/{@link #warn}: {@code key} per la dedupe lato client, {@code firstOfSeries} se ha generato un toast SSE. */
    public record Recorded(String key, String message, boolean firstOfSeries, boolean transientFailure,
                           SystemEventSeverity severity) {

        public Recorded(String key, String message, boolean firstOfSeries, boolean transientFailure) {
            this(key, message, firstOfSeries, transientFailure, SystemEventSeverity.ERROR);
        }
    }

    /** Stato della campanella: non visualizzati (almeno WARNING), gli ultimi {@value #BELL_ITEMS} e se c'e' almeno un ERROR. */
    public record Unseen(long count, List<SystemEvent> latest, boolean hasError) {
    }

    static final int BELL_ITEMS = 5;
    private static final SystemEventSeverity BELL_MINIMUM = SystemEventSeverity.WARNING;

    private final SystemEventRepository repository;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher eventPublisher;
    private final Messages messages;
    private final ObjectMapper objectMapper;
    private final Duration warningSeriesWindow;

    public SystemEventService(SystemEventRepository repository, PlatformTransactionManager transactionManager,
                            ApplicationEventPublisher eventPublisher, Messages messages, ObjectMapper objectMapper,
                            @Value("${app.events.warning-series-window:24h}") Duration warningSeriesWindow) {
        this.warningSeriesWindow = warningSeriesWindow;
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.eventPublisher = eventPublisher;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    /**
     * Come {@link #record(EventSource, String, Throwable)}, con la source ricavata dall'eccezione
     * ({@link RemoteServiceException#source()}, anche se incapsulata; {@code INTERNAL} per il resto): il chiamante non
     * deve conoscere il servizio da cui viene l'errore.
     */
    public Recorded record(String operation, Throwable error) {
        return record(sourceOf(error), operation, error, null);
    }

    /** {@code subject}: a cosa si riferisce l'evento (es. {@code generation:12}, vedi {@link SystemEvent}), puo' essere {@code null}. */
    public Recorded record(String operation, Throwable error, String subject) {
        return record(sourceOf(error), operation, error, subject);
    }

    /** {@link #record(String, Throwable, String)} + toast nella risposta htmx (il boilerplate dei controller). */
    public Recorded recordForHtmx(HttpServletResponse response, String operation, Throwable error) {
        return recordForHtmx(response, operation, error, null);
    }

    public Recorded recordForHtmx(HttpServletResponse response, String operation, Throwable error, String subject) {
        Recorded recorded = record(operation, error, subject);
        addToastHeader(response, recorded);
        return recorded;
    }

    /** La source dell'eccezione remota (anche nella catena delle cause), {@code INTERNAL} se non lo e'. */
    public static EventSource sourceOf(Throwable error) {
        RemoteServiceException remote = remoteCause(error);
        return remote != null ? remote.source() : CoreEventSource.INTERNAL;
    }

    private static RemoteServiceException remoteCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RemoteServiceException remote) {
                return remote;
            }
        }
        return null;
    }

    public Recorded record(EventSource source, String operation, Throwable error) {
        return record(source, operation, error, null);
    }

    public Recorded record(EventSource source, String operation, Throwable error, String subject) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        boolean transientFailure = remoteCause(error) != null && remoteCause(error).isTransient();
        String message = sanitize(error);
        log.warn("Errore [{}] {} ({}): {}", source, operation, subject, message, error);
        return store(SystemEventSeverity.ERROR, source, operation, type, message, stack(error), subject,
                toast(source, message), transientFailure);
    }

    /**
     * Registra un AVVISO (severita' WARNING): qualcosa che richiede attenzione prima che diventi un guasto (es. un token in
     * scadenza). {@code message} e' gia' tradotto e completo; {@code subject} (es. {@code token:12}) identifica la causa e separa
     * le serie fra loro; {@code operation} dice cosa l'ha prodotto (es. {@code tokenExpiring}). Stessa semantica di
     * {@link #record}: non lancia mai, un solo toast per serie (finestra {@code app.events.warning-series-window}).
     */
    public Recorded warn(EventSource source, String operation, String subject, String message) {
        String text = sanitizeText(message);
        log.warn("Avviso [{}] {} ({}): {}", source, operation, subject, text);
        return store(SystemEventSeverity.WARNING, source, operation, "Warning", text, null, subject,
                toastWarning(source, text), false);
    }

    private Recorded store(SystemEventSeverity severity, EventSource source, String operation, String type, String message,
                           String details, String subject, String toastMessage, boolean transientFailure) {
        String key = UUID.randomUUID().toString();
        boolean first = true;
        try {
            Instant now = Instant.now();
            Duration window = severity == SystemEventSeverity.WARNING ? warningSeriesWindow : SERIES_WINDOW;
            Saved saved = transaction.execute(status -> {
                var open = repository.findOpenSeries(source.name(), operation, type, severity, now.minus(window), subject,
                        Pageable.ofSize(1));
                if (!open.isEmpty()) {
                    SystemEvent existing = open.get(0);
                    existing.repeat(message, details, now);
                    repository.save(existing);
                    return new Saved(existing.getId(), false);
                }
                SystemEvent created = repository.save(new SystemEvent(severity, source, operation, type, message, details,
                        subject, now));
                return new Saved(created.getId(), true);
            });
            if (saved != null) {
                key = "e" + saved.id() + (saved.first() ? "" : "-" + UUID.randomUUID());
                first = saved.first();
            }
        } catch (RuntimeException registryFailure) {
            // Il registro non deve mai far fallire il chiamante: si perde solo la riga, resta il log sopra.
            log.error("Impossibile salvare l'evento nel registro: {}", registryFailure.toString());
        }
        if (first) {
            try {
                eventPublisher.publishEvent(new SystemToastEvent(key, toastMessage, transientFailure, severity));
            } catch (RuntimeException publishFailure) {
                log.error("Impossibile pubblicare il toast dell'evento: {}", publishFailure.toString());
            }
        }
        return new Recorded(key, toastMessage, first, transientFailure, severity);
    }

    /** Stato della campanella (vedi {@link Unseen}). */
    public Unseen unseen() {
        var severities = SystemEventSeverity.atLeast(BELL_MINIMUM);
        long count = repository.countBySeverityInAndAcknowledgedAtIsNull(severities);
        if (count == 0) {
            return new Unseen(0, List.of(), false);
        }
        return new Unseen(count, repository.findTop5BySeverityInAndAcknowledgedAtIsNullOrderByLastSeenAtDesc(severities),
                repository.countBySeverityAndAcknowledgedAtIsNull(SystemEventSeverity.ERROR) > 0);
    }

    public void markSeen(Long id) {
        repository.markSeen(id, Instant.now());
    }

    public void markAllSeen() {
        repository.markAllSeen(Instant.now());
    }

    /** Toglie dalla campanella gli eventi non letti riferiti a {@code subject} (es. dopo aver rinnovato/cancellato un token). */
    public void markSeenBySubject(String subject) {
        repository.markSeenBySubject(subject, Instant.now());
    }

    /**
     * Aggiunge alla risposta htmx l'header {@code HX-Trigger} che fa comparire il toast
     * (evento {@code system-toast}, vedi fragments/toast.html). Sempre presente, anche per una
     * ripetizione di serie: e' l'utente che ha appena provato un'azione e deve saperne l'esito.
     */
    public void addToastHeader(HttpServletResponse response, Recorded recorded) {
        Map<String, Object> toast = new java.util.LinkedHashMap<>();
        toast.put("key", recorded.key());
        toast.put("message", recorded.message());
        toast.put("transient", recorded.transientFailure());
        toast.put("severity", recorded.severity().name());
        addHxTrigger(response, "system-toast", toast);
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

    private String toast(EventSource source, String message) {
        String shortMessage = message.length() > MAX_TOAST ? message.substring(0, MAX_TOAST) + "…" : message;
        try {
            return messages.get("toast.event.message", messages.get("events.source." + source.name()), shortMessage);
        } catch (RuntimeException e) {
            return source.name() + ": " + shortMessage;
        }
    }

    private String toastWarning(EventSource source, String message) {
        String shortMessage = message.length() > MAX_TOAST ? message.substring(0, MAX_TOAST) + "…" : message;
        try {
            return messages.get("toast.event.warning", messages.get("events.source." + source.name()), shortMessage);
        } catch (RuntimeException e) {
            return source.name() + ": " + shortMessage;
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
        return sanitizeText(text);
    }

    private static String sanitizeText(String text) {
        text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
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
