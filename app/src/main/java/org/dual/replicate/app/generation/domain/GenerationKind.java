package org.dual.replicate.app.generation.domain;

/**
 * Tipo di media prodotto da una {@link Generation}: le immagini restano il
 * caso di default, il video (vedi {@link GenerationFormType#P_VIDEO},
 * migrazione V12) usa la stessa pipeline asincrona ma cambia il rendering
 * dell'output (tag video invece di img) e i tempi di attesa.
 */
public enum GenerationKind {
    IMAGE,
    VIDEO
}
