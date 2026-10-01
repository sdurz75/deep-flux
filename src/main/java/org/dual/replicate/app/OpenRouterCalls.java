package org.dual.replicate.app;

import org.dual.replicate.app.shared.domain.OpenRouterException;
import org.dual.replicate.core.kernel.remote.RemoteCaller;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.kernel.remote.RestClientTranslator;
import org.dual.replicate.core.kernel.remote.RetryPolicy;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Esecutore delle chiamate a {@code ChatClient} (OpenRouter), condiviso dagli adapter AI di chat e prompt. Spring AI ritenta GIA' da
 * se' gli errori transitori dentro il proprio retry (a livello HTTP, prima che qualunque tool venga eseguito;
 * {@code spring.ai.retry.*}): qui non si aggiunge un secondo strato ({@link RetryPolicy#NONE}, un ritentativo dell'intero turno
 * rieseguirebbe i tool, cioe' generazioni a pagamento), ci si limita a dare a ogni errore il tipo/la source/il {@link Kind} comuni.
 * Sta nel package radice dell'app (non in una feature) proprio perche' e' di entrambe.
 */
public final class OpenRouterCalls {

    /** Da usare per ogni chiamata a {@code ChatClient}: {@code OpenRouterCalls.CALLER.call("chatTurn", () -> ...)}. */
    public static final RemoteCaller CALLER = RemoteCaller.builder(OpenRouterCalls::translate)
            .retry(RetryPolicy.NONE).build();

    private OpenRouterCalls() {
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
