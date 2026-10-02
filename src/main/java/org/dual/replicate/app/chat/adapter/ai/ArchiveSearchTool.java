package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.app.search.port.in.IArchiveSearch;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tool del modello di /deep-chat: ricerca SEMANTICA nell'archivio dell'utente (prompt delle immagini/video generati, messaggi
 * delle conversazioni passate, titoli) sull'archivio ({@link IArchiveSearch}). Come {@link WebSearchTool}, un guasto non rompe il turno:
 * e' registrato e il modello riceve un testo d'errore.
 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveSearchTool {

    private static final Set<String> TYPES = Set.of(DocumentTypes.GENERATION, DocumentTypes.CHAT, DocumentTypes.CONVERSATION);
    private static final int SNIPPET = 300;

    private final IArchiveSearch search;
    private final ISystemEvents systemEvents;
    private final int topK;

    public ArchiveSearchTool(IArchiveSearch search, ISystemEvents systemEvents, @Value("${app.search.top-k:5}") int topK) {
        this.search = search;
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
            DocumentFilter filter = DocumentFilter.NONE;
            if (type != null && !type.isBlank()) {
                String wanted = type.strip().toLowerCase(java.util.Locale.ROOT);
                if (!TYPES.contains(wanted)) {
                    return "Tipo non valido (" + type + "): usa generation, chat o conversation, oppure ometti il filtro.";
                }
                filter = DocumentFilter.ofType(wanted);
            }
            List<ScoredDocument> found = search.search(query, filter, 0.0, topK);
            if (found.isEmpty()) {
                return "Nessun risultato nell'archivio.";
            }
            return found.stream().map(ArchiveSearchTool::describe).collect(java.util.stream.Collectors.joining("\n"));
        } catch (RuntimeException e) {
            systemEvents.record("searchArchive", e);
            return "Ricerca nell'archivio non disponibile al momento (" + ISystemEvents.sanitize(e)
                    + "). Rispondi senza, dicendo all'utente che la ricerca non e' andata a buon fine.";
        }
    }

    private static String describe(ScoredDocument result) {
        Map<String, Object> metadata = result.document().metadata();
        String type = String.valueOf(metadata.get("type"));
        Object refId = metadata.get("refId");
        Object conversationId = metadata.get("conversationId");
        String text = DocumentTypes.visibleText(result.document().text()).replaceAll("\\s+", " ");
        String snippet = text.length() > SNIPPET ? text.substring(0, SNIPPET) + "…" : text;
        return switch (type) {
            case DocumentTypes.GENERATION -> "- [generation #%s] (/generations/%s) %s".formatted(refId, refId, snippet);
            case DocumentTypes.CONVERSATION -> "- [conversation #%s] %s".formatted(refId, snippet);
            default -> "- [chat, conversation #%s, %s] %s".formatted(conversationId, metadata.get("role"), snippet);
        };
    }
}
