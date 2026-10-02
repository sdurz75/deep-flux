package org.dual.replicate.core.events.application;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.dual.replicate.core.events.domain.EventLink;
import org.dual.replicate.core.events.domain.EventPage;
import org.dual.replicate.core.events.domain.SystemEvent;
import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.port.out.IEventLinkResolver;
import org.dual.replicate.core.events.port.out.ISystemEventStore;
import org.dual.replicate.core.events.port.out.IToastNotifier;
import org.dual.replicate.core.kernel.EventSource;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementazione di {@link ISystemEvents} (vedi CLAUDE.md, "Errori ed eventi di sistema").
 * <p>
 * {@code record} fa tre cose: (1) logga con stack, (2) salva/aggiorna una riga {@link SystemEvent} (transazione propria,
 * REQUIRES_NEW: sopravvive al rollback del chiamante), (3) se e' la prima occorrenza di una serie, notifica un toast a tutte le tab
 * ({@link IToastNotifier}). NON lancia mai: un fallimento del registro stesso viene solo loggato, altrimenti il codice di gestione
 * errori creerebbe nuovi errori.
 * <p>
 * Serie: lo stesso evento (severity+source+operation+subject+tipo di eccezione) ripetuto entro la finestra aggiorna la riga
 * esistente (occurrences++) senza nuovo toast: il polling di /generations/{id} ogni 2s durante un'outage non deve produrre
 * centinaia di righe/toast. La finestra e' {@link #SERIES_WINDOW} (5 min) per gli ERRORI e
 * {@code app.events.warning-series-window} (24h) per gli AVVISI: un controllo periodico non deve ripetere lo stesso toast ogni
 * pochi minuti.
 * <p>
 * Locale: il messaggio del toast e' risolto con la locale del thread corrente (vedi {@link Messages}); i thread async che
 * vogliono la locale della richiesta d'origine la impostano prima (vedi ChatGenerationWatcher).
 */
@Service
public class SystemEventService implements ISystemEvents {

    private static final Logger log = LoggerFactory.getLogger(SystemEventService.class);

    static final Duration SERIES_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_TOAST = 200;
    private static final int MAX_DETAILS = 8000;

    static final int BELL_ITEMS = 5;
    private static final SystemEventSeverity BELL_MINIMUM = SystemEventSeverity.WARNING;

    private final ISystemEventStore store;
    private final TransactionTemplate transaction;
    private final IToastNotifier toasts;
    private final Messages messages;
    private final Duration warningSeriesWindow;
    private final Optional<IEventLinkResolver> linkResolver;

    public SystemEventService(ISystemEventStore store, PlatformTransactionManager transactionManager, IToastNotifier toasts,
                              Messages messages, @Value("${app.events.warning-series-window:24h}") Duration warningSeriesWindow,
                              Optional<IEventLinkResolver> linkResolver) {
        this.warningSeriesWindow = warningSeriesWindow;
        this.linkResolver = linkResolver;
        this.store = store;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.toasts = toasts;
        this.messages = messages;
    }

    @Override
    public Recorded record(String operation, Throwable error) {
        return record(ISystemEvents.sourceOf(error), operation, error, null);
    }

    @Override
    public Recorded record(String operation, Throwable error, String subject) {
        return record(ISystemEvents.sourceOf(error), operation, error, subject);
    }

    @Override
    public Recorded record(EventSource source, String operation, Throwable error) {
        return record(source, operation, error, null);
    }

    @Override
    public Recorded record(EventSource source, String operation, Throwable error, String subject) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        RemoteServiceException remote = ISystemEvents.remoteCause(error);
        boolean transientFailure = remote != null && remote.isTransient();
        String message = ISystemEvents.sanitize(error);
        log.warn("Errore [{}] {} ({}): {}", source, operation, subject, message, error);
        return store(SystemEventSeverity.ERROR, source, operation, type, message, stack(error), subject,
                toast("toast.event.message", source, message), transientFailure);
    }

    @Override
    public Recorded warn(EventSource source, String operation, String subject, String message) {
        String text = ISystemEvents.sanitizeText(message);
        log.warn("Avviso [{}] {} ({}): {}", source, operation, subject, text);
        return store(SystemEventSeverity.WARNING, source, operation, "Warning", text, null, subject,
                toast("toast.event.warning", source, text), false);
    }

    private Recorded store(SystemEventSeverity severity, EventSource source, String operation, String type, String message,
                           String details, String subject, String toastMessage, boolean transientFailure) {
        String key = UUID.randomUUID().toString();
        boolean first = true;
        try {
            Instant now = Instant.now();
            Duration window = severity == SystemEventSeverity.WARNING ? warningSeriesWindow : SERIES_WINDOW;
            Saved saved = transaction.execute(status -> {
                var open = store.findOpenSeries(source.name(), operation, type, severity, now.minus(window), subject);
                if (open.isPresent()) {
                    SystemEvent existing = open.get();
                    existing.repeat(message, details, now);
                    store.save(existing);
                    return new Saved(existing.getId(), false);
                }
                SystemEvent created = store.save(new SystemEvent(severity, source, operation, type, message, details, subject, now));
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
                toasts.notify(key, toastMessage, transientFailure, severity);
            } catch (RuntimeException notifyFailure) {
                log.error("Impossibile notificare il toast dell'evento: {}", notifyFailure.toString());
            }
        }
        return new Recorded(key, toastMessage, first, transientFailure, severity);
    }

    @Override
    public Unseen unseen() {
        var severities = SystemEventSeverity.atLeast(BELL_MINIMUM);
        long count = store.countUnseen(severities);
        if (count == 0) {
            return new Unseen(0, List.of(), false);
        }
        return new Unseen(count, store.latestUnseen(severities, BELL_ITEMS), store.countUnseen(SystemEventSeverity.ERROR) > 0);
    }

    @Override
    public void markSeen(Long id) {
        store.markSeen(id, Instant.now());
    }

    @Override
    public void markAllSeen() {
        store.markAllSeen(Instant.now());
    }

    @Override
    public void markSeenBySubject(String subject) {
        store.markSeenBySubject(subject, Instant.now());
    }

    @Override
    public EventPage list(SystemEventSeverity severity, int pageIndex, int pageSize) {
        return store.page(severity, pageIndex, pageSize);
    }

    @Override
    public Map<Long, List<EventLink>> linksFor(List<SystemEvent> events) {
        Map<Long, List<EventLink>> links = new HashMap<>();
        linkResolver.ifPresent(resolver -> events.forEach(e -> links.put(e.getId(), resolver.resolve(e.getSubject()))));
        return links;
    }

    @Override
    public void clear() {
        store.deleteAll();
    }

    private record Saved(Long id, boolean first) {
    }

    private String toast(String key, EventSource source, String message) {
        String shortMessage = message.length() > MAX_TOAST ? message.substring(0, MAX_TOAST) + "…" : message;
        try {
            return messages.get(key, messages.get("events.source." + source.name()), shortMessage);
        } catch (RuntimeException e) {
            return source.name() + ": " + shortMessage;
        }
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
