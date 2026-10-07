package org.dual.hexa.app.generation.adapter.out.replicate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.dual.hexa.app.generation.domain.ReplicateConfigurationException;
import org.dual.hexa.app.generation.domain.ReplicateException;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RestRemoteClient;
import org.dual.hexa.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Wrapper sottile sulle API REST di Replicate (https://replicate.com/docs/reference/http).
 * Chiamate sincrone via RestClient: niente WebFlux, coerente con lo
 * stack MVC del progetto.
 */
@Component
class ReplicateClient extends RestRemoteClient {

    private final RestClient restClient;
    private final String apiToken;
    private final Messages messages;

    ReplicateClient(RestClient.Builder restClientBuilder,
                            @Value("${replicate.api-base-url}") String apiBaseUrl,
                            @Value("${replicate.api-token}") String apiToken,
                            Messages messages) {
        super("replicate", messages, ReplicateException::new, RetryPolicy.DEFAULT);
        this.restClient = restClientBuilder.baseUrl(apiBaseUrl).build();
        this.apiToken = apiToken;
        this.messages = messages;
    }

    /**
     * Crea una prediction. Se {@code version} e' presente usa l'endpoint
     * generico /v1/predictions (richiede l'hash di versione); altrimenti
     * usa /v1/models/{owner}/{name}/predictions, che gira sull'ultima
     * versione pubblicata del modello senza bisogno di conoscerne l'hash.
     */
    public PredictionResponse createPrediction(String model, String version, Map<String, Object> input) {
        requireToken();
        String path;
        Map<String, Object> body = new LinkedHashMap<>();
        if (version != null && !version.isBlank()) {
            path = "/predictions";
            body.put("version", version);
        } else {
            String[] ownerAndName = model.split("/", 2);
            if (ownerAndName.length != 2) {
                throw new ReplicateConfigurationException(messages.get("replicate.error.invalidModelFormat"));
            }
            path = "/models/%s/%s/predictions".formatted(ownerAndName[0], ownerAndName[1]);
        }
        body.put("input", input);

        // NON idempotente e a pagamento: un ritentativo dopo un timeout potrebbe creare una seconda prediction.
        return remote.call("createPrediction", RetryPolicy.NONE, () -> requireBody(restClient.post()
                .uri(path)
                .headers(this::authHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PredictionResponse.class)));
    }

    public PredictionResponse getPrediction(String externalId) {
        requireToken();
        return remote.call("getPrediction", () -> requireBody(restClient.get()
                .uri("/predictions/{id}", externalId)
                .headers(this::authHeaders)
                .retrieve()
                .body(PredictionResponse.class)));
    }

    /** Il modello {@code "owner/name"} (GET /models/{owner}/{name}); vuoto se non esiste (404). Sola lettura: si ritenta sui transitori. */
    public Optional<ModelResponse> getModel(String model) {
        requireToken();
        String[] ownerAndName = model.split("/", 2);
        if (ownerAndName.length != 2) {
            throw new ReplicateConfigurationException(messages.get("replicate.error.invalidModelFormat"));
        }
        return remote.call("getModel", () -> {
            try {
                return Optional.ofNullable(restClient.get()
                        .uri("/models/{owner}/{name}", ownerAndName[0], ownerAndName[1])
                        .headers(this::authHeaders)
                        .retrieve()
                        .body(ModelResponse.class));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.<ModelResponse>empty();
            }
        });
    }

    /**
     * Chiede a Replicate di interrompere una prediction in corso
     * (POST /predictions/{id}/cancel). Se la prediction e' gia' terminale
     * Replicate risponde con un errore HTTP, che qui diventa una
     * ReplicateException come ogni altro.
     */
    public PredictionResponse cancelPrediction(String externalId) {
        requireToken();
        return remote.call("cancelPrediction", () -> requireBody(restClient.post()
                .uri("/predictions/{id}/cancel", externalId)
                .headers(this::authHeaders)
                .retrieve()
                .body(PredictionResponse.class)));
    }

    /** Un 2xx con corpo vuoto non e' una risposta utilizzabile: errore riprovabile, mai un null che poi esplode altrove. */
    private PredictionResponse requireBody(PredictionResponse response) {
        if (response == null) {
            throw new ReplicateException(messages.get("replicate.error.emptyResponse"), null, true);
        }
        return response;
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiToken);
    }

    private void requireToken() {
        if (apiToken == null || apiToken.isBlank()) {
            throw new ReplicateConfigurationException(messages.get("replicate.error.tokenMissing"));
        }
    }
}
