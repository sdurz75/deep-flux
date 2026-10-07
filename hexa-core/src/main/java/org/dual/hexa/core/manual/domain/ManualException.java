package org.dual.hexa.core.manual.domain;

/** Un guasto del manuale (testi illeggibili, due pagine con lo stesso slug): e' un errore di installazione, non un input dell'utente. */
public class ManualException extends RuntimeException {

    public ManualException(String message) {
        super(message);
    }

    public ManualException(String message, Throwable cause) {
        super(message, cause);
    }
}
