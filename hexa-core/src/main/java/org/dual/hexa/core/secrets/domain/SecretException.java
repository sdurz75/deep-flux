package org.dual.hexa.core.secrets.domain;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore della cifratura dei segreti ({@code SecretCipher}): chiave di cifratura mancante/errata o dato manomesso, cioe'
 * {@code Kind.CONFIGURATION} (non e' input dell'utente, va notificato). Come {@code TokenException} e' un
 * {@link RemoteServiceException} con source TOKENS, quindi il resolver e il registro eventi lo trattano allo stesso modo.
 */
public class SecretException extends RemoteServiceException {

    public SecretException(String message, Throwable cause) {
        super(CoreEventSource.TOKENS, Kind.CONFIGURATION, message, cause);
    }
}
