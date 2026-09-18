package org.dual.replicate.service;

/**
 * Errore applicativo verso l'assistente chat (token OpenRouter mancante,
 * chiamata al modello fallita...). Intercettato dal controller e tradotto
 * in un fragment/pagina HTML con messaggio d'errore, mai in un 500 generico.
 */
public class ChatException extends RuntimeException {

    public ChatException(String message) {
        super(message);
    }

    public ChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
