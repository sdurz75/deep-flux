package org.dual.hexa.ai.llm.domain;

import org.dual.hexa.ai.llm.domain.OpenRouterException;

/**
 * Il modello di visione (e il suo fallback) ha rifiutato di descrivere l'immagine:
 * la "risposta" e' un rifiuto, non un prompt, e non deve finire nella textarea.
 */
public class PromptEnhancementRefusedException extends OpenRouterException {

    public PromptEnhancementRefusedException(String message) {
        super(message, null, Kind.REJECTED);
    }
}
