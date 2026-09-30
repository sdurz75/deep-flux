package org.dual.replicate.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * Riga del registro errori (vedi V17, AppErrorService, pagina /errors).
 * Una "serie" di errori identici ravvicinati e' una sola riga con
 * {@link #getOccurrences()} incrementato.
 */
@Entity
@Table(name = "app_error")
public class AppError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private int occurrences = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppErrorSource source;

    @Column(nullable = false, length = 100)
    private String operation;

    @Column(nullable = false, length = 200)
    private String errorType;

    @Column(nullable = false, length = 2000)
    private String message;

    @Lob
    private String details;

    private Long generationId;

    private Long conversationId;

    protected AppError() {
        // richiesto da JPA
    }

    public AppError(AppErrorSource source, String operation, String errorType, String message, String details,
                    Long generationId, Long conversationId, Instant now) {
        this.source = source;
        this.operation = operation;
        this.errorType = errorType;
        this.message = message;
        this.details = details;
        this.generationId = generationId;
        this.conversationId = conversationId;
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

    public AppErrorSource getSource() {
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

    public Long getGenerationId() {
        return generationId;
    }

    public Long getConversationId() {
        return conversationId;
    }
}
