package org.dual.replicate.service;

import org.dual.replicate.core.events.port.in.ISystemEvents;

/**
 * Il turno di /deep-chat e' fallito ed e' GIA' stato registrato (ISystemEvents) e
 * scritto come turno d'errore in cronologia: chi la cattura (DeepChatApiController) deve solo
 * mostrare {@link #getMessage()} all'utente, senza registrarla una seconda volta.
 */
public class DeepChatFailedException extends RuntimeException {

    public DeepChatFailedException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
