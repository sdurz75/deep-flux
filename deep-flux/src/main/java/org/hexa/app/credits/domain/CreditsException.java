package org.hexa.app.credits.domain;

import org.hexa.app.shared.domain.AppEventSource;
import org.hexa.core.kernel.remote.RemoteServiceException;

/** Saldo Replicate non valido: sempre un rifiuto atteso (messaggio gia' tradotto), mai un evento di sistema. */
public class CreditsException extends RemoteServiceException {

    public CreditsException(String message) {
        super(AppEventSource.REPLICATE, Kind.REJECTED, message, null);
    }
}
