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
    public record Recorded(String key, String message, boolean firstOfSeries) {
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

    public Recorded record(AppErrorSource source, String operation, Throwable error) {
        return record(source, operation, error, null, null);
    }

    public Recorded record(AppErrorSource source, String operation, Throwable error, Long generationId, Long conversationId) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
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
                eventPublisher.publishEvent(new ErrorToastEvent(key, toastMessage));
            } catch (RuntimeException publishFailure) {
                log.error("Impossibile pubblicare il toast d'errore: {}", publishFailure.toString());
            }
        }
        return new Recorded(key, toastMessage, first);
    }

    /**
     * Aggiunge alla risposta htmx l'header {@code HX-Trigger} che fa comparire il toast
     * (evento {@code app-error}, vedi fragments/toast.html). Sempre presente, anche per una
     * ripetizione di serie: e' l'utente che ha appena provato un'azione e deve saperne l'esito.
     */
    public void addToastHeader(HttpServletResponse response, Recorded recorded) {
        try {
            response.setHeader("HX-Trigger", objectMapper.writeValueAsString(
                    Map.of("app-error", Map.of("key", recorded.key(), "message", recorded.message()))));
        } catch (RuntimeException e) {
            log.error("Impossibile costruire l'header HX-Trigger del toast: {}", e.toString());
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
