package org.dual.replicate.core.credits.adapter.out.openrouter;

import java.math.BigDecimal;

import org.dual.replicate.core.credits.port.out.IOpenRouterCreditGateway;
import org.dual.replicate.core.ai.domain.OpenRouterException;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.kernel.remote.RestRemoteClient;
import org.dual.replicate.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Credito OpenRouter ({@code GET /api/v1/credits}, https://openrouter.ai/docs/api/api-reference/credits/get-credits). L'endpoint vuole
 * una MANAGEMENT key (non l'{@code OPENROUTER_API_TOKEN} della chat, che da' 403): senza {@code openrouter.management-key} il gateway
 * e' semplicemente non configurato. Lettura idempotente: ritenta i soli errori transitori.
 */
@Component
class OpenRouterCreditsClient extends RestRemoteClient implements IOpenRouterCreditGateway {

    private final RestClient restClient;
    private final String managementKey;
    private final Messages messages;

    OpenRouterCreditsClient(RestClient.Builder restClientBuilder,
                            @Value("${openrouter.base-url}") String baseUrl,
                            @Value("${openrouter.management-key:}") String managementKey,
                            Messages messages) {
        super("openrouter", messages, OpenRouterException::new, RetryPolicy.DEFAULT);
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.managementKey = managementKey;
        this.messages = messages;
    }

    @Override
    public boolean isConfigured() {
        return managementKey != null && !managementKey.isBlank();
    }

    @Override
    public BigDecimal remaining() {
        if (!isConfigured()) {
            throw new OpenRouterException(messages.get("openrouter.error.managementKeyMissing"), null, Kind.CONFIGURATION);
        }
        return remote.call("getCredits", () -> {
            CreditsResponse response = restClient.get()
                    .uri("/credits")
                    .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + managementKey))
                    .retrieve()
                    .body(CreditsResponse.class);
            if (response == null || response.data() == null || response.data().totalCredits() == null) {
                throw new OpenRouterException(messages.get("openrouter.error.unreadableResponse"), null, Kind.PERMANENT);
            }
            BigDecimal used = response.data().totalUsage() == null ? BigDecimal.ZERO : response.data().totalUsage();
            return response.data().totalCredits().subtract(used);
        });
    }
}
