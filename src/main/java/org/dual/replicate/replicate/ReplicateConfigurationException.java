package org.dual.replicate.replicate;

/**
 * Errore di configurazione (token mancante, modello malformato): non e' una validazione dell'input dell'utente,
 * va registrato e notificato (SystemEventService) anche se non ha una causa a monte.
 */
public class ReplicateConfigurationException extends ReplicateException {

    public ReplicateConfigurationException(String message) {
        super(message, null, Kind.CONFIGURATION);
    }
}
