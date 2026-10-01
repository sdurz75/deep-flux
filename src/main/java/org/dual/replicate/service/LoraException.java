package org.dual.replicate.service;

import org.dual.replicate.domain.SystemEventSource;
import org.dual.replicate.remote.RemoteServiceException;

/** Errore della gestione dei LoRA anagrafati: sempre un rifiuto atteso (validazione, "non trovato"), solo un messaggio all'utente. */
public class LoraException extends RemoteServiceException {

    public LoraException(String message, Throwable cause, Kind kind) {
        super(SystemEventSource.LORAS, kind, message, cause);
    }

    /** Rifiuto applicativo atteso (messaggio gia' tradotto). */
    public LoraException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
