package org.dual.replicate.search;

import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;

/**
 * Errore applicativo verso SearXNG (credenziali mancanti, istanza irraggiungibile, formato JSON non abilitato lato
 * server...). Lasciato propagare dal tool di web search: il comportamento di default di Spring AI lo rimanda al modello
 * come messaggio d'errore, che puo' reagire senza rompere la conversazione.
 */
public class SearxngException extends RemoteServiceException {

    /** Credenziali mancanti: configurazione. */
    public SearxngException(String message) {
        super(AppEventSource.SEARXNG, Kind.CONFIGURATION, message, null);
    }

    public SearxngException(String message, Throwable cause, Kind kind) {
        super(AppEventSource.SEARXNG, kind, message, cause);
    }
}
