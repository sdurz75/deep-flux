package org.dual.replicate.replicate;

import org.dual.replicate.domain.AppErrorSource;
import org.dual.replicate.remote.RemoteServiceException;

/**
 * Errore applicativo verso Replicate (token mancante, modello non valido, chiamata HTTP fallita...) e, per estensione,
 * rifiuto di una richiesta di generazione (modello sconosciuto, sorgente mancante...). Intercettato dai controller e
 * tradotto in un fragment/pagina HTML con messaggio d'errore, mai in un 500 generico.
 * <p>
 * Il {@link Kind} decide tutto (vedi {@link RemoteServiceException}): {@code TRANSIENT} (rete, timeout, 5xx, 429) fa
 * riprovare e lascia in corso una generazione il cui poll fallisce; {@code PERMANENT} (token errato, 4xx, risposta
 * illeggibile) la fallisce subito; {@code REJECTED} e' un rifiuto atteso, mai registrato.
 */
public class ReplicateException extends RemoteServiceException {

    /** Rifiuto applicativo (validazione): non e' un errore di comunicazione. */
    public ReplicateException(String message) {
        this(message, null, Kind.REJECTED);
    }

    /** Errore vero con una causa (chiamata fallita, riga non salvabile...). */
    public ReplicateException(String message, Throwable cause) {
        this(message, cause, Kind.PERMANENT);
    }

    public ReplicateException(String message, Throwable cause, boolean transientFailure) {
        this(message, cause, transientFailure ? Kind.TRANSIENT : Kind.PERMANENT);
    }

    public ReplicateException(String message, Throwable cause, Kind kind) {
        super(AppErrorSource.REPLICATE, kind, message, cause);
    }
}
