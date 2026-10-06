package org.dual.replicate.app.training.domain;

/** Chi ha scritto la didascalia di un'immagine: nessuno ancora, il modello di visione o l'utente (che non viene mai sovrascritto in automatico). */
public enum CaptionSource {
    NONE,
    AUTO,
    MANUAL
}
