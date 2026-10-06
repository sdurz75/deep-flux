package org.dual.replicate.core.ai.adapter.out.openrouter;

import org.dual.replicate.core.ai.domain.OpenRouterException;
import org.dual.replicate.core.ai.port.in.IAiCalls;
import org.dual.replicate.core.kernel.remote.RemoteCaller;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.kernel.remote.RestClientTranslator;
import org.dual.replicate.core.kernel.remote.RetryPolicy;
import org.dual.replicate.core.kernel.remote.ThrowingSupplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** {@link IAiCalls} per OpenRouter: nessun ritentativo (vedi la porta), solo la traduzione degli errori. */
@Component
public class OpenRouterAiCalls implements IAiCalls {

    private static final RemoteCaller CALLER = RemoteCaller.builder(OpenRouterAiCalls::translate)
            .retry(RetryPolicy.NONE).build();

    @Override
    public <T> T call(String operation, ThrowingSupplier<T> call) {
        return CALLER.call(operation, call);
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
