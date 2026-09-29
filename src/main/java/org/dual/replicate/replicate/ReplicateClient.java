package org.dual.replicate.replicate;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Wrapper sottile sulle API REST di Replicate (https://replicate.com/docs/reference/http).
 * Chiamate sincrone via RestClient: niente WebFlux, coerente con lo
 * stack MVC del progetto.
 */
@Component
public class ReplicateClient {

    private final RestClient restClient;
    private final String apiToken;
    private final Messages messages;

    public ReplicateClient(RestClient.Builder restClientBuilder,
                            @Value("${replicate.api-base-url}") String apiBaseUrl,
                            @Value("${replicate.api-token}") String apiToken,
                            Messages messages) {
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
                throw new ReplicateException(messages.get("replicate.error.invalidModelFormat"));
            }
            path = "/models/%s/%s/predictions".formatted(ownerAndName[0], ownerAndName[1]);
        }
        body.put("input", input);

        try {
            return restClient.post()
                    .uri(path)
                    .headers(this::authHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(PredictionResponse.class);
        } catch (RestClientException e) {
            throw toReplicateException(e);
        }
    }

    public PredictionResponse getPrediction(String externalId) {
        requireToken();
        try {
            return restClient.get()
                    .uri("/predictions/{id}", externalId)
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(PredictionResponse.class);
        } catch (RestClientException e) {
            throw toReplicateException(e);
        }
    }

    /**
     * Chiede a Replicate di interrompere una prediction in corso
     * (POST /predictions/{id}/cancel). Se la prediction e' gia' terminale
     * Replicate risponde con un errore HTTP, che qui diventa una
     * ReplicateException come ogni altro.
     */
    public PredictionResponse cancelPrediction(String externalId) {
        requireToken();
        try {
            return restClient.post()
                    .uri("/predictions/{id}/cancel", externalId)
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(PredictionResponse.class);
        } catch (RestClientException e) {
            throw toReplicateException(e);
        }
    }

    /** Traduce un errore RestClient (HTTP non-2xx o connessione fallita) in un messaggio leggibile. */
    private ReplicateException toReplicateException(RestClientException e) {
        if (e instanceof RestClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            return new ReplicateException(messages.get("replicate.error.httpError",
                    responseException.getStatusCode(), body.isBlank() ? responseException.getMessage() : body), e);
        }
        return new ReplicateException(messages.get("replicate.error.connectionFailed", e.getMessage()), e);
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBearerAuth(apiToken);
    }

    private void requireToken() {
        if (apiToken == null || apiToken.isBlank()) {
            throw new ReplicateException(messages.get("replicate.error.tokenMissing"));
        }
    }
}
