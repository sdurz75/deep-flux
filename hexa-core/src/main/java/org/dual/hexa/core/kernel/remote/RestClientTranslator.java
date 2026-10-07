package org.dual.hexa.core.kernel.remote;

import java.util.function.Function;

import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Traduzione unica degli errori di {@code RestClient} in {@link RemoteServiceException}, uguale per ogni servizio: una
 * risposta 408/429/5xx (salvo 507) o l'assenza di risposta (rete, timeout) e' TRANSITORIA; un altro 4xx o una risposta
 * illeggibile e' PERMANENTE. I messaggi vengono dal bundle con le chiavi {@code <prefix>.error.httpError} ({0} = stato,
 * {1} = corpo o messaggio) e {@code <prefix>.error.connectionFailed} ({0} = messaggio).
 */
public final class RestClientTranslator implements Function<Throwable, RemoteServiceException> {

    /** Costruisce l'eccezione del servizio (es. {@code ReplicateException::new}). */
    @FunctionalInterface
    public interface ExceptionFactory {
        RemoteServiceException create(String message, Throwable cause, RemoteServiceException.Kind kind);
    }

    private final String prefix;
    private final Messages messages;
    private final ExceptionFactory factory;

    public RestClientTranslator(String i18nPrefix, Messages messages, ExceptionFactory factory) {
        this.prefix = i18nPrefix;
        this.messages = messages;
        this.factory = factory;
    }

    @Override
    public RemoteServiceException apply(Throwable error) {
        if (error instanceof RestClientResponseException response) {
            String body = response.getResponseBodyAsString();
            return factory.create(messages.get(prefix + ".error.httpError", response.getStatusCode(),
                    body.isBlank() ? response.getMessage() : body), error, kindOfStatus(response.getStatusCode().value()));
        }
        // Nessuna risposta HTTP: rete/timeout/connessione (riprovabile) oppure corpo non decodificabile (non lo e').
        boolean network = error instanceof ResourceAccessException;
        return factory.create(messages.get(prefix + ".error.connectionFailed", error.getMessage()), error,
                network ? RemoteServiceException.Kind.TRANSIENT : RemoteServiceException.Kind.PERMANENT);
    }

    /** Per gli errori HTTP notati DENTRO una callback di {@code exchange} (dove non c'e' una RestClientResponseException). */
    public RemoteServiceException httpStatus(int status, String detail) {
        return factory.create(messages.get(prefix + ".error.httpError", status, detail), null, kindOfStatus(status));
    }

    /** 408/429/5xx sono riprovabili, salvo 507 (spazio esaurito: ritentare non lo libera). */
    public static RemoteServiceException.Kind kindOfStatus(int status) {
        boolean transientStatus = status == 408 || status == 429 || (status >= 500 && status != 507);
        return transientStatus ? RemoteServiceException.Kind.TRANSIENT : RemoteServiceException.Kind.PERMANENT;
    }
}
