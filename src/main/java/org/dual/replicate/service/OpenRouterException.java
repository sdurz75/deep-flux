package org.dual.replicate.service;

import org.dual.replicate.domain.SystemEventSource;
import org.dual.replicate.remote.RemoteCaller;
import org.dual.replicate.remote.RemoteServiceException;
import org.dual.replicate.remote.RetryPolicy;
import org.dual.replicate.remote.RestClientTranslator;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Errore di una chiamata all'LLM (OpenRouter via Spring AI). Spring AI ritenta GIA' da se' gli errori transitori dentro il
 * proprio retry (a livello HTTP, prima che qualunque tool venga eseguito; {@code spring.ai.retry.*}): qui non si aggiunge un secondo
 * strato ({@link RetryPolicy#NONE}, un ritentativo dell'intero turno rieseguirebbe i tool, cioe' generazioni a pagamento),
 * ci si limita a dare a ogni errore il tipo/la source/il {@link Kind} comuni.
 */
public class OpenRouterException extends RemoteServiceException {

    /** Da usare per ogni chiamata a {@code ChatClient}: {@code OpenRouterException.CALLER.call("chatTurn", () -> ...)}. */
    public static final RemoteCaller CALLER = RemoteCaller.builder(OpenRouterException::translate)
            .retry(RetryPolicy.NONE).build();

    public OpenRouterException(String message, Throwable cause, Kind kind) {
        super(SystemEventSource.OPENROUTER, kind, message, cause);
    }

    /**
     * Il tipo esatto dipende dalla versione di Spring AI: si guarda la catena delle cause. Una risposta HTTP 408/429/5xx o
     * l'assenza di risposta (rete, timeout) e' transitoria, il resto (4xx, risposta vuota, rifiuto) permanente.
     */
    public static OpenRouterException translate(Throwable error) {
        Kind kind = Kind.PERMANENT;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RestClientResponseException response) {
                kind = RestClientTranslator.kindOfStatus(response.getStatusCode().value());
                break;
            }
            if (t instanceof ResourceAccessException) {
                kind = Kind.TRANSIENT;
                break;
            }
        }
        return new OpenRouterException(error.getMessage(), error, kind);
    }
}
