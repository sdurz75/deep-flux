package org.dual.replicate.replicate;

/**
 * Errore applicativo verso Replicate (token mancante, modello non
 * valido, chiamata HTTP fallita...). Intercettato dai controller e
 * tradotto in un fragment/pagina HTML con messaggio d'errore, mai in un
 * 500 generico.
 * <p>
 * {@link #isTransient()}: true per un fallimento che ha senso riprovare
 * (rete, timeout, 5xx, 429), false per uno permanente (token errato, 4xx,
 * risposta illeggibile). Decide se un poll fallito fa fallire la
 * generazione subito o resta in attesa del prossimo tentativo (vedi
 * GenerationService#refresh).
 */
public class ReplicateException extends RuntimeException {

    private final boolean transientFailure;

    public ReplicateException(String message) {
        this(message, null, false);
    }

    public ReplicateException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ReplicateException(String message, Throwable cause, boolean transientFailure) {
        super(message, cause);
        this.transientFailure = transientFailure;
    }

    public boolean isTransient() {
        return transientFailure;
    }
}
