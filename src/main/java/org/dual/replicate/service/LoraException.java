package org.dual.replicate.service;

import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;

/** Errore della gestione dei LoRA anagrafati: sempre un rifiuto atteso (validazione, "non trovato"), solo un messaggio all'utente. */
public class LoraException extends RemoteServiceException {

    public LoraException(String message, Throwable cause, Kind kind) {
        super(AppEventSource.LORAS, kind, message, cause);
    }

    /** Rifiuto applicativo atteso (messaggio gia' tradotto). */
    public LoraException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
