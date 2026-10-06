package org.dual.replicate.core.ai.domain;

import org.dual.replicate.core.ai.domain.OpenRouterException;

/**
 * La didascalia di un'immagine non e' stata prodotta: il modello di visione (e il suo fallback) ha rifiutato o ha risposto qualcosa di inutilizzabile.
 * Rifiuto atteso ({@code REJECTED}), non un guasto del servizio: chi chiama segna la didascalia come non riuscita, senza un evento di sistema.
 */
public class ImageCaptionException extends OpenRouterException {

    public ImageCaptionException(String message, Throwable cause) {
        super(message, cause, Kind.REJECTED);
    }
}
