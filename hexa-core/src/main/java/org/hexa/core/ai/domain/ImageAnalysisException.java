package org.hexa.core.ai.domain;

import org.hexa.core.ai.domain.OpenRouterException;

/**
 * L'analisi di contenuto non ha prodotto una descrizione utilizzabile: il modello di visione (e il suo fallback) ha rifiutato o ha
 * risposto in un formato illeggibile. Rifiuto atteso ({@code REJECTED}), non un guasto del servizio.
 */
public class ImageAnalysisException extends OpenRouterException {

    public ImageAnalysisException(String message, Throwable cause) {
        super(message, cause, Kind.REJECTED);
    }
}
