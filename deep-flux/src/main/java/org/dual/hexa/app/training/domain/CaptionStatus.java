package org.dual.hexa.app.training.domain;

/**
 * Stato del lavoro di auto-caption di un'immagine. {@code DONE} vuol dire solo "nessun lavoro in corso": la didascalia puo' essere vuota
 * (si controlla sul testo, al lancio del training).
 */
public enum CaptionStatus {
    PENDING,
    DONE,
    FAILED
}
