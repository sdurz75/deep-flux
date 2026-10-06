package org.dual.replicate.core.events.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.dual.replicate.core.events.domain.EventPage;
import org.dual.replicate.core.events.domain.SystemEvent;
import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.events.port.out.ISystemEventStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/** {@link ISystemEventStore} su Spring Data JPA (tabella {@code system_event}). */
@Component
class JpaSystemEventStore implements ISystemEventStore {

    private final SystemEventRepository repository;

    JpaSystemEventStore(SystemEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public SystemEvent save(SystemEvent event) {
        return repository.save(event);
    }

    @Override
    public Optional<SystemEvent> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<SystemEvent> findOpenSeries(String source, String operation, String errorType, SystemEventSeverity severity,
                                                Instant after, String subject) {
        return repository.findOpenSeries(source, operation, errorType, severity, after, subject, Pageable.ofSize(1)).stream()
                .findFirst();
    }

    @Override
    public EventPage page(SystemEventSeverity severity, int pageIndex, int pageSize) {
        PageRequest pageable = PageRequest.of(pageIndex, pageSize);
        Page<SystemEvent> page = severity == null
                ? repository.findAllByOrderByLastSeenAtDesc(pageable)
                : repository.findAllBySeverityOrderByLastSeenAtDesc(severity, pageable);
        return new EventPage(page.getContent(), page.getTotalPages(), page.hasPrevious(), page.hasNext());
    }

    @Override
    public List<SystemEvent> findAll() {
        return repository.findAll();
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void deleteAll() {
        repository.deleteAllInBatch();
    }

    @Override
    public long countUnseen(Set<SystemEventSeverity> severities) {
        return repository.countBySeverityInAndAcknowledgedAtIsNull(severities);
    }

    @Override
    public long countUnseen(SystemEventSeverity severity) {
        return repository.countBySeverityAndAcknowledgedAtIsNull(severity);
    }

    @Override
    public List<SystemEvent> latestUnseen(Set<SystemEventSeverity> severities, int limit) {
        return repository.findBySeverityInAndAcknowledgedAtIsNullOrderByLastSeenAtDesc(severities, PageRequest.ofSize(limit));
    }

    @Override
    public int markSeen(Long id, Instant now) {
        return repository.markSeen(id, now);
    }

    @Override
    public int markAllSeen(Instant now) {
        return repository.markAllSeen(now);
    }

    @Override
    public int markSeenBySubject(String subject, Instant now) {
        return repository.markSeenBySubject(subject, now);
    }
}
