package org.dual.replicate.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.dual.replicate.core.kernel.EventSource;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Riga del registro eventi di sistema (vedi V17/V21/V22, SystemEventService, pagina /system/events).
 * Una "serie" di eventi identici ravvicinati e' una sola riga con
 * {@link #getOccurrences()} incrementato. {@code subject} (es. "token:12") dice a cosa si riferisce
 * l'evento ed entra nella chiave di serie; {@code acknowledgedAt} valorizzato = visualizzato (campanella).
 * Una ripetizione di una serie gia' visualizzata NON torna "non visualizzata".
 */
@Entity
@Table(name = "system_event")
public class SystemEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private int occurrences = 1;

    /** {@link EventSource#name()}: stringa, cosi' il core non conosce i valori dell'app. */
    @Column(nullable = false, length = 20)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SystemEventSeverity severity = SystemEventSeverity.ERROR;

    @Column(nullable = false, length = 100)
    private String operation;

    @Column(nullable = false, length = 200)
    private String errorType;

    @Column(nullable = false, length = 2000)
    private String message;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String details;

    @Column(length = 100)
    private String subject;

    private Instant acknowledgedAt;

    protected SystemEvent() {
        // richiesto da JPA
    }

    /** Un ERROR senza subject (il caso di tutti gli errori registrati finora). */
    public SystemEvent(EventSource source, String operation, String errorType, String message, String details, Instant now) {
        this(SystemEventSeverity.ERROR, source, operation, errorType, message, details, null, now);
    }

    public SystemEvent(SystemEventSeverity severity, EventSource source, String operation, String errorType, String message,
                    String details, String subject, Instant now) {
        this.severity = severity;
        this.subject = subject;
        this.source = source.name();
        this.operation = operation;
        this.errorType = errorType;
        this.message = message;
        this.details = details;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /** Un'altra occorrenza della stessa serie: aggiorna conteggio, ultimo avvistamento e messaggio piu' recente. */
    public void repeat(String latestMessage, String latestDetails, Instant now) {
        this.occurrences++;
        this.lastSeenAt = now;
        this.message = latestMessage;
        this.details = latestDetails;
    }

    /** Segna l'evento come visualizzato (idempotente). */
    public void acknowledge(Instant now) {
        if (acknowledgedAt == null) {
            acknowledgedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public String getSource() {
        return source;
    }

    public String getOperation() {
        return operation;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    public SystemEventSeverity getSeverity() {
        return severity;
    }

    public String getSubject() {
        return subject;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }
}
