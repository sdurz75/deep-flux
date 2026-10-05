package org.dual.replicate.app.training.domain;

/** Stato di un training (i termini di Replicate: {@code starting|processing|succeeded|failed|canceled}). Gli ultimi tre sono terminali. */
public enum TrainingStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
