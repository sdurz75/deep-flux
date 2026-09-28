package org.dual.replicate.replicate;

import java.net.URI;
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

    /** Tetto sul numero di pagine seguite da countInProgressPredictions. */
    private static final int LIST_PAGE_CAP = 5;

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

    /**
     * Conta le prediction "in esecuzione" (status starting/processing)
     * sull'intero account, fermandosi non appena il conteggio raggiunge
     * {@code stopAt} (usata da GenerationService per rifiutare una nuova
     * generazione oltre soglia, non serve il conteggio esatto oltre
     * quella). GET /v1/predictions non supporta un filtro per stato: va
     * paginato (100 risultati a pagina, i piu' recenti prima) e filtrato
     * qui. LIST_PAGE_CAP limita quante pagine si seguono, per non
     * paginare indefinitamente su un account con molto storico concluso.
     */
    public int countInProgressPredictions(int stopAt) {
        requireToken();
        int inProgress = 0;
        PredictionListResponse page = getFirstPredictionsPage();
        for (int pageCount = 1; ; pageCount++) {
            for (PredictionResponse prediction : page.results()) {
                if ("starting".equals(prediction.status()) || "processing".equals(prediction.status())) {
                    inProgress++;
                    if (inProgress >= stopAt) {
                        return inProgress;
                    }
                }
            }
            if (page.next() == null || pageCount >= LIST_PAGE_CAP) {
                return inProgress;
            }
            page = getNextPredictionsPage(page.next());
        }
    }

    private PredictionListResponse getFirstPredictionsPage() {
        try {
            return restClient.get()
                    .uri("/predictions")
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(PredictionListResponse.class);
        } catch (RestClientException e) {
            throw toReplicateException(e);
        }
    }

    /**
     * "next" e' gia' un URL assoluto e completamente codificato: passarlo
     * come template stringa (la forma usata da tutti gli altri metodi) lo
     * ri-codificherebbe (es. "&" diventerebbe "%26"), rompendo il cursore
     * di paginazione. Va passato come URI gia' pronto.
     */
    private PredictionListResponse getNextPredictionsPage(String next) {
        try {
            return restClient.get()
                    .uri(URI.create(next))
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(PredictionListResponse.class);
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
