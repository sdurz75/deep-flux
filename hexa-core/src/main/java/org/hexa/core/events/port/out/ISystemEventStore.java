package org.hexa.core.events.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hexa.core.events.domain.EventPage;
import org.hexa.core.events.domain.SystemEvent;
import org.hexa.core.events.domain.SystemEventSeverity;

/** Persistenza del registro eventi di sistema. */
public interface ISystemEventStore {

    SystemEvent save(SystemEvent event);

    Optional<SystemEvent> findById(Long id);

    /**
     * Serie "aperta" dello stesso evento (la piu' recente). Il predicato su {@code subject} e' null-safe: {@code null} combacia
     * solo con eventi senza subject (un confronto SQL {@code = null} non combacia mai e ogni evento creerebbe riga e toast nuovi).
     */
    Optional<SystemEvent> findOpenSeries(String source, String operation, String errorType, SystemEventSeverity severity,
                                         Instant after, String subject);

    /** Registro paginato, ultimo avvistamento prima; {@code severity} {@code null} = tutte. */
    EventPage page(SystemEventSeverity severity, int pageIndex, int pageSize);

    List<SystemEvent> findAll();

    long count();

    void deleteAll();

    /** Campanella: eventi non ancora visualizzati con severita' tra {@code severities}. */
    long countUnseen(Set<SystemEventSeverity> severities);

    long countUnseen(SystemEventSeverity severity);

    /** Gli ultimi {@code limit} non visualizzati, ultimo avvistamento prima. */
    List<SystemEvent> latestUnseen(Set<SystemEventSeverity> severities, int limit);

    int markSeen(Long id, Instant now);

    int markAllSeen(Instant now);

    int markSeenBySubject(String subject, Instant now);
}
