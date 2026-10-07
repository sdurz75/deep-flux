package org.dual.hexa.core.events.port.in;

import java.util.List;
import java.util.Map;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.domain.EventLink;
import org.dual.hexa.core.events.domain.EventPage;
import org.dual.hexa.core.events.domain.SystemEvent;
import org.dual.hexa.core.events.domain.SystemEventSeverity;
import org.dual.hexa.core.kernel.EventSource;
import org.dual.hexa.core.kernel.ToastMessage;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Unico punto di registrazione degli eventi di sistema (vedi CLAUDE.md, "Errori ed eventi di sistema"): gli ERRORI delle
 * chiamate remote e quelli interni non gestiti ({@code record}: ogni {@code catch} che riguarda un servizio esterno passa da
 * qui invece di ingoiare l'eccezione o loggarla a mano) e gli AVVISI ({@link #warn}, es. un token in scadenza).
 * <p>
 * {@code record} e {@code warn} NON lanciano mai: un fallimento del registro stesso viene solo loggato, altrimenti il codice
 * di gestione errori creerebbe nuovi errori. {@code subject} (es. {@code generation:12}, {@code token:12}) dice a cosa si
 * riferisce l'evento ed entra nella chiave di serie. Il lato HTTP (header HX-Trigger del toast) NON sta qui: vedi
 * {@code core.web.HtmxEvents}.
 */
public interface ISystemEvents {

    /** Esito di {@code record}/{@code warn}: {@code key} per la dedupe lato client, {@code firstOfSeries} se ha generato un toast. */
    record Recorded(String key, String message, boolean firstOfSeries, boolean transientFailure, SystemEventSeverity severity)
            implements ToastMessage {

        public Recorded(String key, String message, boolean firstOfSeries, boolean transientFailure) {
            this(key, message, firstOfSeries, transientFailure, SystemEventSeverity.ERROR);
        }

        @Override
        public String severityName() {
            return severity.name();
        }
    }

    /** Stato della campanella: non visualizzati (almeno WARNING), gli ultimi e se c'e' almeno un ERROR. */
    record Unseen(long count, List<SystemEvent> latest, boolean hasError) {
    }

    /** Con la source ricavata dall'eccezione ({@link RemoteServiceException#source()}, anche se incapsulata; INTERNAL per il resto). */
    Recorded record(String operation, Throwable error);

    Recorded record(String operation, Throwable error, String subject);

    Recorded record(EventSource source, String operation, Throwable error);

    Recorded record(EventSource source, String operation, Throwable error, String subject);

    /** Registra un AVVISO (WARNING); {@code message} e' gia' tradotto e completo. Un solo toast per serie. */
    Recorded warn(EventSource source, String operation, String subject, String message);

    Unseen unseen();

    void markSeen(Long id);

    void markAllSeen();

    /** Toglie dalla campanella gli eventi non letti riferiti a {@code subject} (es. dopo aver rinnovato/cancellato un token). */
    void markSeenBySubject(String subject);

    /** Una pagina del registro, piu' recente prima; {@code severity} {@code null} = tutte. */
    EventPage list(SystemEventSeverity severity, int pageIndex, int pageSize);

    /**
     * I link "apri" per evento (id -> link), che l'app ricava dal {@code subject} dell'evento. Senza un'app che li fornisca
     * ({@code IEventLinkResolver}) la mappa e' vuota: il core funziona comunque.
     */
    Map<Long, List<EventLink>> linksFor(List<SystemEvent> events);

    /** Svuota il registro. */
    void clear();

    /** La source dell'eccezione remota (anche nella catena delle cause), INTERNAL se non lo e'. */
    static EventSource sourceOf(Throwable error) {
        RemoteServiceException remote = remoteCause(error);
        return remote != null ? remote.source() : CoreEventSource.INTERNAL;
    }

    /** La prima {@link RemoteServiceException} nella catena delle cause, {@code null} se non c'e'. */
    static RemoteServiceException remoteCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RemoteServiceException remote) {
                return remote;
            }
        }
        return null;
    }

    /** Messaggio breve e su una riga, mai null. */
    static String sanitize(Throwable error) {
        if (error == null) {
            return "Errore sconosciuto";
        }
        String text = error.getMessage();
        if (text == null || text.isBlank()) {
            text = error.getClass().getSimpleName();
        }
        return sanitizeText(text);
    }

    /** Testo su una riga, mai null, al massimo {@value #MAX_MESSAGE} caratteri. */
    static String sanitizeText(String text) {
        text = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE) + "…" : text;
    }

    int MAX_MESSAGE = 500;
}
