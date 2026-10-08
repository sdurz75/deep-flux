package org.dual.hexa.core.secrets.domain;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore dei segreti (CRUD, cifratura, risoluzione per una generazione). {@code Kind.REJECTED} per un esito atteso (nome duplicato, segreto scaduto o
 * inesistente: solo un messaggio all'utente, niente registro), {@code CONFIGURATION} per la chiave di cifratura mancante/errata o un dato manomesso (non
 * e' input dell'utente, va notificato). Source {@code SECRETS}, quindi il resolver e il registro eventi lo trattano come ogni
 * {@link RemoteServiceException}.
 */
public class SecretException extends RemoteServiceException {

    public SecretException(String message, Throwable cause, Kind kind) {
        super(CoreEventSource.SECRETS, kind, message, cause);
    }

    /** Guasto di cifratura: {@code CONFIGURATION}. */
    public SecretException(String message, Throwable cause) {
        this(message, cause, Kind.CONFIGURATION);
    }

    /** Rifiuto applicativo atteso (messaggio gia' tradotto). */
    public SecretException(String message) {
        this(message, null, Kind.REJECTED);
    }
}
