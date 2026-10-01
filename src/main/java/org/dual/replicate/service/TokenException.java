package org.dual.replicate.service;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.remote.RemoteServiceException;

/**
 * Errore della gestione dei token API (CRUD, cifratura, risoluzione per una generazione). {@code Kind.REJECTED} per un esito
 * atteso (nome duplicato, token scaduto o inesistente: solo un messaggio all'utente, niente registro), {@code CONFIGURATION}
 * per la chiave di cifratura mancante/errata (va notificata).
 */
public class TokenException extends RemoteServiceException {

    public TokenException(String message, Throwable cause, Kind kind) {
        super(CoreEventSource.TOKENS, kind, message, cause);
    }

    /** Rifiuto applicativo atteso (messaggio gia' tradotto). */
    public TokenException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
