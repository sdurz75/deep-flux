package org.dual.replicate.replicate;

/**
 * Errore applicativo verso Replicate (token mancante, modello non
 * valido, chiamata HTTP fallita...). Intercettato dai controller e
 * tradotto in un fragment/pagina HTML con messaggio d'errore, mai in un
 * 500 generico.
 */
public class ReplicateException extends RuntimeException {

    public ReplicateException(String message) {
        super(message);
    }

    public ReplicateException(String message, Throwable cause) {
        super(message, cause);
    }
}
