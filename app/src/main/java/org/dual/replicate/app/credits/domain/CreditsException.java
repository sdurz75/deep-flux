package org.dual.replicate.app.credits.domain;

import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;

/** Saldo Replicate non valido: sempre un rifiuto atteso (messaggio gia' tradotto), mai un evento di sistema. */
public class CreditsException extends RemoteServiceException {

    public CreditsException(String message) {
        super(AppEventSource.REPLICATE, Kind.REJECTED, message, null);
    }
}
