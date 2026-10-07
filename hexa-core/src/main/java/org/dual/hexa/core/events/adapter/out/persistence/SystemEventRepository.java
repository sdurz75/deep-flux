package org.dual.hexa.core.events.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.dual.hexa.core.events.domain.SystemEvent;
import org.dual.hexa.core.events.domain.SystemEventSeverity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code ISystemEventStore}. */
interface SystemEventRepository extends JpaRepository<SystemEvent, Long> {

    /**
     * Serie "aperta" dello stesso evento (piu' recente prima; passare {@code Pageable.ofSize(1)}). Il predicato su
     * {@code subject} e' null-safe A MANO: un JPQL {@code e.x = :x} con :x null non combacia mai, e ogni evento senza subject
     * creerebbe una riga e un toast nuovi (il metodo derivato dal nome lo faceva da se').
     */
    @Query("""
            select e from SystemEvent e
            where e.source = :source and e.operation = :operation and e.errorType = :errorType
              and e.severity = :severity and e.lastSeenAt > :after
              and ((:subject is null and e.subject is null) or e.subject = :subject)
            order by e.id desc
            """)
    List<SystemEvent> findOpenSeries(@Param("source") String source, @Param("operation") String operation,
                                     @Param("errorType") String errorType, @Param("severity") SystemEventSeverity severity,
                                     @Param("after") Instant after, @Param("subject") String subject, Pageable pageable);

    /** Listato di /system/events: ultimo avvistamento prima. */
    Page<SystemEvent> findAllByOrderByLastSeenAtDesc(Pageable pageable);

    Page<SystemEvent> findAllBySeverityOrderByLastSeenAtDesc(SystemEventSeverity severity, Pageable pageable);

    /** Campanella: eventi non ancora visualizzati con severita' tra {@code severities}. */
    long countBySeverityInAndAcknowledgedAtIsNull(Collection<SystemEventSeverity> severities);

    long countBySeverityAndAcknowledgedAtIsNull(SystemEventSeverity severity);

    List<SystemEvent> findBySeverityInAndAcknowledgedAtIsNullOrderByLastSeenAtDesc(Collection<SystemEventSeverity> severities,
                                                                                   Pageable pageable);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SystemEvent e set e.acknowledgedAt = :now where e.id = :id and e.acknowledgedAt is null")
    int markSeen(@Param("id") Long id, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SystemEvent e set e.acknowledgedAt = :now where e.acknowledgedAt is null")
    int markAllSeen(@Param("now") Instant now);

    /** Quando la causa e' rimossa (es. un token rinnovato o cancellato) i suoi avvisi non letti non devono restare nella campanella. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SystemEvent e set e.acknowledgedAt = :now where e.subject = :subject and e.acknowledgedAt is null")
    int markSeenBySubject(@Param("subject") String subject, @Param("now") Instant now);
}
