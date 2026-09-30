package org.dual.replicate.service;

import java.util.List;
import java.util.stream.Collectors;

import org.dual.replicate.domain.AppErrorSource;
import org.dual.replicate.search.SearchResult;
import org.dual.replicate.search.SearxngClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool Spring AI registrato sul ChatClient di DeepChatService: il
 * modello decide da solo se e quando invocarlo (nessuna ricerca
 * automatica ad ogni messaggio). Qualunque errore della ricerca viene
 * catturato QUI (non ci si affida al comportamento di default di Spring
 * AI per le eccezioni dei tool): registrato in AppErrorService (tabella
 * errori + toast) e rimandato al modello come testo, cosi' il turno
 * continua e l'utente ne vede comunque l'esito.
 */
@Component
public class WebSearchTool {

    private static final int MAX_RESULTS = 5;

    private final SearxngClient searxngClient;
    private final AppErrorService appErrors;

    public WebSearchTool(SearxngClient searxngClient, AppErrorService appErrors) {
        this.searxngClient = searxngClient;
        this.appErrors = appErrors;
    }

    @Tool(description = "Search the public web for current information (news, facts, prices, "
            + "anything that may not be in your training data). Returns a short list of "
            + "results with title, URL and a text snippet.")
    public String searchWeb(@ToolParam(description = "The search query, in the language most likely to return good results") String query) {
        List<SearchResult> results;
        try {
            results = searxngClient.search(query);
        } catch (RuntimeException e) {
            appErrors.record("search", e);
            return "Ricerca web non disponibile al momento (" + AppErrorService.sanitize(e)
                    + "). Rispondi senza, dicendo all'utente che la ricerca non e' andata a buon fine.";
        }
        if (results.isEmpty()) {
            return "Nessun risultato trovato.";
        }
        return results.stream()
                .limit(MAX_RESULTS)
                .map(r -> "- %s (%s): %s".formatted(orEmpty(r.title()), orEmpty(r.url()), orEmpty(r.content())))
                .collect(Collectors.joining("\n"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
