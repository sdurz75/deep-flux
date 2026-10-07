package org.hexa.core.chat.adapter.out.searxng;

import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore applicativo verso SearXNG (credenziali mancanti, istanza irraggiungibile, formato JSON non abilitato lato
 * server...). Lasciato propagare dal tool di web search: il comportamento di default di Spring AI lo rimanda al modello
 * come messaggio d'errore, che puo' reagire senza rompere la conversazione.
 */
public class SearxngException extends RemoteServiceException {

    /** Credenziali mancanti: configurazione. */
    public SearxngException(String message) {
        super(CoreEventSource.SEARXNG, Kind.CONFIGURATION, message, null);
    }

    public SearxngException(String message, Throwable cause, Kind kind) {
        super(CoreEventSource.SEARXNG, kind, message, cause);
    }
}
