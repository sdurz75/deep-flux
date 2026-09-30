package org.dual.replicate.search;

import java.util.List;

import org.dual.replicate.i18n.Messages;
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
    private final Messages messages;

    public SearxngClient(RestClient.Builder restClientBuilder,
                          @Value("${searxng.base-url}") String baseUrl,
                          @Value("${searxng.username}") String username,
                          @Value("${searxng.password}") String password,
                          Messages messages) {
        // Normalizzato con slash finale: sotto e' risolto come path
        // relativo "search" (mai "/search"), e UriComponentsBuilder
        // concatena senza inserire un separatore - senza lo slash qui,
        // un base-url senza slash finale (es. ".../xng" invece di
        // ".../xng/") risolverebbe silenziosamente in ".../xngsearch".
        // Timeout PROPRI e piu' stretti dei globali (spring.http.clients.*): la ricerca gira DENTRO un turno
        // LLM, un SearXNG appeso terrebbe aperto il thread e l'intero turno ben oltre il limite dell'LLM.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        org.springframework.http.client.JdkClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(20));
        this.restClient = restClientBuilder.requestFactory(requestFactory)
                .baseUrl(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/").build();
        this.username = username;
        this.password = password;
        this.messages = messages;
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
        } catch (RuntimeException e) {
            // Risposta non decodificabile (HttpMessageNotReadableException), URI non valido...: qualunque cosa
            // esca da qui deve essere una SearxngException, l'unico tipo che il chiamante sa gestire.
            throw new SearxngException(messages.get("searxng.error.connectionFailed", e.getMessage()), e);
        }
    }

    private SearxngException toSearxngException(RestClientException e) {
        if (e instanceof RestClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            return new SearxngException(messages.get("searxng.error.httpError",
                    responseException.getStatusCode(), body.isBlank() ? responseException.getMessage() : body), e);
        }
        return new SearxngException(messages.get("searxng.error.connectionFailed", e.getMessage()), e);
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBasicAuth(username, password);
    }

    private void requireCredentials() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new SearxngException(messages.get("searxng.error.credentialsMissing"));
        }
    }
}
