package org.hexa.app.generation.domain;

/**
 * Errore di configurazione (token mancante, modello malformato): non e' una validazione dell'input dell'utente,
 * va registrato e notificato (ISystemEvents) anche se non ha una causa a monte.
 */
public class ReplicateConfigurationException extends ReplicateException {

    public ReplicateConfigurationException(String message) {
        super(message, null, Kind.CONFIGURATION);
    }
}
