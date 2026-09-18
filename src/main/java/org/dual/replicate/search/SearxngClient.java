package org.dual.replicate.search;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Wrapper sottile sull'API di ricerca di una istanza SearXNG
 * (https://docs.searxng.org/dev/search_api.html), usata dal tool di web
 * search del modello su /deep-chat. Chiamate sincrone via RestClient:
 * niente WebFlux, coerente con lo stack MVC del progetto (stesso
 * pattern di ReplicateClient).
 */
@Component
public class SearxngClient {

    private final RestClient restClient;
    private final String username;
    private final String password;

    public SearxngClient(RestClient.Builder restClientBuilder,
                          @Value("${searxng.base-url}") String baseUrl,
                          @Value("${searxng.username}") String username,
                          @Value("${searxng.password}") String password) {
        // Normalizzato con slash finale: sotto e' risolto come path
        // relativo "search" (mai "/search"), e UriComponentsBuilder
        // concatena senza inserire un separatore - senza lo slash qui,
        // un base-url senza slash finale (es. ".../xng" invece di
        // ".../xng/") risolverebbe silenziosamente in ".../xngsearch".
        this.restClient = restClientBuilder.baseUrl(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/").build();
        this.username = username;
        this.password = password;
    }

    /**
     * Path relativo "search", senza slash iniziale: il base url ha
     * gia' il sotto-path dell'istanza (es. "/xng/"), un path assoluto
     * "/search" lo sovrascriverebbe risolvendo dalla root del dominio.
     */
    public List<SearchResult> search(String query) {
        requireCredentials();
        try {
            SearxngResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .build())
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(SearxngResponse.class);
            return response == null || response.results() == null ? List.of() : response.results();
        } catch (RestClientException e) {
            throw toSearxngException(e);
        }
    }

    private SearxngException toSearxngException(RestClientException e) {
        if (e instanceof RestClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            return new SearxngException("SearXNG ha risposto con errore (%s): %s"
                    .formatted(responseException.getStatusCode(), body.isBlank() ? responseException.getMessage() : body), e);
        }
        return new SearxngException("Impossibile contattare SearXNG: " + e.getMessage(), e);
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBasicAuth(username, password);
    }

    private void requireCredentials() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new SearxngException("SEARXNG_USERNAME/SEARXNG_PASSWORD non impostati: esporta le variabili d'ambiente prima di avviare l'app.");
        }
    }
}
