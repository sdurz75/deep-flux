package org.hexa.core.ai.domain;

import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.core.kernel.remote.RemoteServiceException;

/**
 * Errore di una chiamata all'LLM (OpenRouter via Spring AI). Il tipo/la source/il {@link Kind} sono comuni a chat e
 * "AI enhance"; la traduzione di un errore di libreria in questo tipo (che guarda i tipi HTTP) e' di {@code IAiCalls}.
 */
public class OpenRouterException extends RemoteServiceException {

    public OpenRouterException(String message, Throwable cause, Kind kind) {
        super(CoreEventSource.OPENROUTER, kind, message, cause);
    }
}
