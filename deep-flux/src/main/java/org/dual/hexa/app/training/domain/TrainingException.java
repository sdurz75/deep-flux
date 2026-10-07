package org.dual.hexa.app.training.domain;

import org.dual.hexa.app.shared.domain.AppEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore della gestione di dataset e training: di norma un rifiuto atteso (validazione, "non trovato", dataset congelato), solo un messaggio
 * all'utente; con {@code Kind} esplicito un guasto vero (zip, file) che finisce nel registro eventi.
 */
public class TrainingException extends RemoteServiceException {

    public TrainingException(String message, Throwable cause, Kind kind) {
        super(AppEventSource.TRAINING, kind, message, cause);
    }

    /** Rifiuto applicativo atteso (messaggio gia' tradotto). */
    public TrainingException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
