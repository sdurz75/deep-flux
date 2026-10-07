package org.hexa.app.generation.domain;

/** Stato dell'analisi di contenuto (modello di visione) di un'immagine importata. Le generate non ne hanno uno. */
public enum AnalysisStatus {
    /** Da fare o in corso: la descrizione non c'e' ancora. */
    PENDING,
    /** Descrizione e tag disponibili (la descrizione e' in {@link Generation#getPrompt()}). */
    DONE,
    /** Non riuscita (rifiuto del modello, risposta illeggibile, guasto): l'immagine resta valida, l'analisi si puo' ritentare. */
    FAILED
}
