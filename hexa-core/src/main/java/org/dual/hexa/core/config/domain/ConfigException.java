package org.dual.hexa.core.config.domain;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Rifiuto ATTESO di un salvataggio di configurazione (valore non valido, modulo sconosciuto): e' un {@code REJECTED}, quindi niente registro eventi;
 * il controller delle impostazioni lo mostra nel pannello del modulo.
 */
public class ConfigException extends RemoteServiceException {

    public ConfigException(String message) {
        super(CoreEventSource.INTERNAL, Kind.REJECTED, message, null);
    }
}
