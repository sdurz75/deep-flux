package org.dual.replicate.app.prompt.domain;

/** L'immagine sorgente non si e' potuta ridurre per il modello di visione (dati corrotti): meglio fallire che inviarla intera a un modello a pagamento. */
public class ImageScalingException extends RuntimeException {

    public ImageScalingException(String message, Throwable cause) {
        super(message, cause);
    }
}
