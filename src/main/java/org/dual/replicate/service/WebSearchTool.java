package org.dual.replicate.service;

import java.util.List;
import java.util.stream.Collectors;

import org.dual.replicate.search.SearchResult;
import org.dual.replicate.search.SearxngClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool Spring AI registrato sul ChatClient di DeepChatService: il
 * modello decide da solo se e quando invocarlo (nessuna ricerca
 * automatica ad ogni messaggio). Un'eventuale SearxngException lanciata
 * da SearxngClient viene lasciata propagare: il comportamento di
 * default di Spring AI la rimanda al modello come messaggio d'errore.
 */
@Component
public class WebSearchTool {

    private static final int MAX_RESULTS = 5;

    private final SearxngClient searxngClient;

    public WebSearchTool(SearxngClient searxngClient) {
        this.searxngClient = searxngClient;
    }

    @Tool(description = "Search the public web for current information (news, facts, prices, "
            + "anything that may not be in your training data). Returns a short list of "
            + "results with title, URL and a text snippet.")
    public String searchWeb(@ToolParam(description = "The search query, in the language most likely to return good results") String query) {
        List<SearchResult> results = searxngClient.search(query);
        if (results.isEmpty()) {
            return "Nessun risultato trovato.";
        }
        return results.stream()
                .limit(MAX_RESULTS)
                .map(r -> "- %s (%s): %s".formatted(r.title(), r.url(), r.content()))
                .collect(Collectors.joining("\n"));
    }
}
