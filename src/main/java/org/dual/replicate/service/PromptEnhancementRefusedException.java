package org.dual.replicate.service;

/**
 * Il modello di visione (e il suo fallback) ha rifiutato di descrivere l'immagine:
 * la "risposta" e' un rifiuto, non un prompt, e non deve finire nella textarea.
 */
public class PromptEnhancementRefusedException extends RuntimeException {

    public PromptEnhancementRefusedException(String message) {
        super(message);
    }
}
