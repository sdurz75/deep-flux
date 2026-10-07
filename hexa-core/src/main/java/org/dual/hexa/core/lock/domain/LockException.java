package org.dual.hexa.core.lock.domain;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Rifiuto ATTESO di un'operazione sul blocco (PIN errato, formato non valido, troppi tentativi): e' un {@code REJECTED}, quindi niente registro eventi;
 * se arriva al resolver del core diventa un 422 con toast, ma i controller del sottosistema lo mostrano nel form.
 */
public class LockException extends RemoteServiceException {

    public LockException(String message) {
        super(CoreEventSource.INTERNAL, Kind.REJECTED, message, null);
    }
}
