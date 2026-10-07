package org.dual.hexa.ai.chat.adapter.out.searxng;

import java.util.List;

import org.dual.hexa.ai.chat.domain.WebSearchResult;
import org.dual.hexa.ai.chat.port.out.IWebSearchGateway;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RestRemoteClient;
import org.dual.hexa.core.kernel.remote.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wrapper sottile sull'API di ricerca di una istanza SearXNG
 * (https://docs.searxng.org/dev/search_api.html), usata dal tool di web
 * search del modello su /deep-chat. Chiamate sincrone via RestClient:
 * niente WebFlux, coerente con lo stack MVC del progetto (stesso
 * pattern di ReplicateClient).
 */
@Component
public class SearxngClient extends RestRemoteClient implements IWebSearchGateway {

    private final RestClient restClient;
    private final boolean configured;
    private final String username;
    private final String password;
    private final Messages messages;

    public SearxngClient(RestClient.Builder restClientBuilder,
                          @Value("${searxng.base-url:}") String baseUrl,
                          @Value("${searxng.username}") String username,
                          @Value("${searxng.password}") String password,
                          Messages messages) {
        // Idempotente ma con timeout stretto (la ricerca gira dentro un turno LLM): un solo ritentativo.
        super("searxng", messages, SearxngException::new, RetryPolicy.of(1, java.time.Duration.ofMillis(300)));
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
        this.configured = !baseUrl.isBlank();
        this.username = username;
        this.password = password;
        this.messages = messages;
    }

    /**
     * Path relativo "search", senza slash iniziale: il base url ha
     * gia' il sotto-path dell'istanza (es. "/xng/"), un path assoluto
     * "/search" lo sovrascriverebbe risolvendo dalla root del dominio.
     */
    @Override
    public List<WebSearchResult> search(String query) {
        requireCredentials();
        return remote.call("search", () -> {
            SearxngResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .build())
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(SearxngResponse.class);
            return response == null || response.results() == null ? List.<WebSearchResult>of()
                    : response.results().stream().map(r -> new WebSearchResult(r.title(), r.url(), r.content())).toList();
        });
    }

    private void authHeaders(HttpHeaders headers) {
        headers.setBasicAuth(username, password);
    }

    private void requireCredentials() {
        if (!configured) {
            throw new SearxngException(messages.get("searxng.error.baseUrlMissing"));
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new SearxngException(messages.get("searxng.error.credentialsMissing"));
        }
    }
}
