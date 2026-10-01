package org.dual.replicate.service;

import java.util.List;
import java.util.Set;

import org.dual.replicate.search.vector.ArchiveIndexService;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tool del modello di /deep-chat: ricerca SEMANTICA nell'archivio dell'utente (prompt delle immagini/video generati, messaggi
 * delle conversazioni passate, titoli) sul {@link VectorStore}. Come {@link WebSearchTool}, un guasto non rompe il turno:
 * e' registrato e il modello riceve un testo d'errore.
 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveSearchTool {

    private static final Set<String> TYPES = Set.of(ArchiveIndexService.TYPE_GENERATION, ArchiveIndexService.TYPE_CHAT,
            ArchiveIndexService.TYPE_CONVERSATION);
    private static final int SNIPPET = 300;

    private final VectorStore vectorStore;
    private final SystemEventService systemEvents;
    private final int topK;

    public ArchiveSearchTool(VectorStore vectorStore, SystemEventService systemEvents, @Value("${app.search.top-k:5}") int topK) {
        this.vectorStore = vectorStore;
        this.systemEvents = systemEvents;
        this.topK = topK;
    }

    @Tool(description = "Search the user's own archive semantically (by meaning, not exact words): prompts of images and "
            + "videos generated in the past, messages of earlier conversations and conversation titles. Use it when the "
            + "user refers to something made or discussed before (\"the cat picture from last week\", \"that prompt "
            + "about the castle\") or asks for something similar to past work. Returns the best matches with their type, "
            + "id and text; generations can be opened at /generations/{id}.")
    public String searchArchive(
            @ToolParam(description = "What to look for, in natural language (Italian or English)") String query,
            @ToolParam(description = "Optional filter: generation, chat or conversation", required = false) String type) {
        try {
            SearchRequest.Builder request = SearchRequest.builder().query(query).topK(topK);
            if (type != null && !type.isBlank()) {
                String wanted = type.strip().toLowerCase(java.util.Locale.ROOT);
                if (!TYPES.contains(wanted)) {
                    return "Tipo non valido (" + type + "): usa generation, chat o conversation, oppure ometti il filtro.";
                }
                request.filterExpression(new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("type"), new Filter.Value(wanted)));
            }
            List<Document> found = vectorStore.similaritySearch(request.build());
            if (found.isEmpty()) {
                return "Nessun risultato nell'archivio.";
            }
            return found.stream().map(ArchiveSearchTool::describe).collect(java.util.stream.Collectors.joining("\n"));
        } catch (RuntimeException e) {
            systemEvents.record("searchArchive", e);
            return "Ricerca nell'archivio non disponibile al momento (" + SystemEventService.sanitize(e)
                    + "). Rispondi senza, dicendo all'utente che la ricerca non e' andata a buon fine.";
        }
    }

    private static String describe(Document document) {
        String type = String.valueOf(document.getMetadata().get("type"));
        Object refId = document.getMetadata().get("refId");
        Object conversationId = document.getMetadata().get("conversationId");
        String text = document.getText().replaceAll("\\s+", " ");
        String snippet = text.length() > SNIPPET ? text.substring(0, SNIPPET) + "…" : text;
        return switch (type) {
            case ArchiveIndexService.TYPE_GENERATION -> "- [generation #%s] (/generations/%s) %s".formatted(refId, refId, snippet);
            case ArchiveIndexService.TYPE_CONVERSATION -> "- [conversation #%s] %s".formatted(refId, snippet);
            default -> "- [chat, conversation #%s, %s] %s".formatted(conversationId, document.getMetadata().get("role"), snippet);
        };
    }
}
