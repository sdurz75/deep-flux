package org.dual.hexa.ai.llm.port.in;

import org.dual.hexa.core.kernel.remote.ThrowingSupplier;

/**
 * Esecutore delle chiamate al provider LLM ({@code ChatClient}), condiviso dagli adapter AI della chat e dei prompt: da' a ogni errore il
 * tipo, la source e il {@code Kind} comuni ({@code OpenRouterException}) e NON ritenta mai: Spring AI ritenta gia' da se' gli errori
 * transitori a livello HTTP (prima di qualunque tool), e un secondo strato rieseguirebbe l'intero turno e i suoi tool (generazioni a
 * pagamento).
 */
public interface IAiCalls {

    /** Esegue {@code call} (es. {@code "chatTurn"}) e traduce ogni eccezione in {@code OpenRouterException}. */
    <T> T call(String operation, ThrowingSupplier<T> call);
}
