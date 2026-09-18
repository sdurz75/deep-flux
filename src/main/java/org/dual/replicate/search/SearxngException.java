package org.dual.replicate.search;

/**
 * Errore applicativo verso SearXNG (credenziali mancanti, istanza
 * irraggiungibile, formato JSON non abilitato lato server...). Lasciato
 * propagare dal tool di web search: il comportamento di default di
 * Spring AI lo rimanda al modello come messaggio d'errore, che puo'
 * reagire senza rompere la conversazione.
 */
public class SearxngException extends RuntimeException {

    public SearxngException(String message) {
        super(message);
    }

    public SearxngException(String message, Throwable cause) {
        super(message, cause);
    }
}
