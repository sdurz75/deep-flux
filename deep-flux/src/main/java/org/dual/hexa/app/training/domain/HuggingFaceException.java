package org.dual.hexa.app.training.domain;

import org.dual.hexa.app.shared.domain.AppEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore verso HuggingFace (token non valido, rete, repo non creabile). Il {@link Kind} decide come si tratta (vedi {@link RemoteServiceException}): un token
 * errato o scaduto e' {@code PERMANENT}, una rete che non risponde {@code TRANSIENT}; un rifiuto atteso e' {@code REJECTED}.
 */
public class HuggingFaceException extends RemoteServiceException {

    public HuggingFaceException(String message, Throwable cause, Kind kind) {
        super(AppEventSource.HUGGINGFACE, kind, message, cause);
    }

    /** Rifiuto applicativo (validazione): non e' un errore di comunicazione. */
    public HuggingFaceException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
