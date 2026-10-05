package org.dual.replicate.app.chat.adapter.ai;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.IndexedDocument;
import org.dual.replicate.app.shared.domain.Tags;
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

    private static final Set<String> TYPES = Set.of(DocumentTypes.GENERATION, DocumentTypes.IMPORTED, DocumentTypes.CHAT, DocumentTypes.CONVERSATION);
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
            + "about the castle\") or asks for something similar to past work. Every item may carry user tags (labels the "
            + "user set by hand): pass `tag` to restrict to items with that exact tag; with an empty query and a tag it "
            + "lists the most recent items with that tag. Returns the best matches with their type, "
            + "id, tags and text; generations can be opened at /generations/{id}, imported images at /import/{id}.")
    public String searchArchive(
            @ToolParam(description = "What to look for, in natural language (Italian or English)") String query,
            @ToolParam(description = "Optional filter: generation, imported, chat or conversation", required = false) String type,
            @ToolParam(description = "Optional user tag (exact, case-insensitive) the items must carry", required = false) String tag) {
        try {
            String wantedType = null;
            if (type != null && !type.isBlank()) {
                wantedType = type.strip().toLowerCase(java.util.Locale.ROOT);
                if (!TYPES.contains(wantedType)) {
                    return "Tipo non valido (" + type + "): usa generation, imported, chat o conversation, oppure ometti il filtro.";
                }
            }
            String wantedTag = Tags.normalize(tag);
            DocumentFilter filter = new DocumentFilter(wantedType, null, null, null, false, wantedTag.isEmpty() ? null : wantedTag);
            if ((query == null || query.isBlank()) && !wantedTag.isEmpty()) {
                // Solo tag: nessuna somiglianza da calcolare, i piu' recenti con quel tag.
                List<IndexedDocument> listed = search.list(filter, 0, topK).content();
                return listed.isEmpty() ? "Nessun risultato nell'archivio."
                        : listed.stream().map(d -> describe(d.metadata(), d.content())).collect(java.util.stream.Collectors.joining("\n"));
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
        return describe(result.document().metadata(), result.document().text());
    }

    private static String describe(Map<String, Object> metadata, String content) {
        String type = String.valueOf(metadata.get("type"));
        Object refId = metadata.get("refId");
        Object conversationId = metadata.get("conversationId");
        String text = DocumentTypes.visibleText(content).replaceAll("\\s+", " ");
        String snippet = text.length() > SNIPPET ? text.substring(0, SNIPPET) + "…" : text;
        String tags = metadata.get("tags") instanceof Collection<?> list && !list.isEmpty() ? " [tags: " + String.join(", ", list.stream().map(String::valueOf).toList()) + "]" : "";
        return switch (type) {
            case DocumentTypes.IMPORTED -> "- [imported image #%s] (/import/%s)%s %s".formatted(refId, refId, tags, snippet);
            case DocumentTypes.GENERATION -> "- [generation #%s] (/generations/%s)%s %s".formatted(refId, refId, tags, snippet);
            case DocumentTypes.CONVERSATION -> "- [conversation #%s]%s %s".formatted(refId, tags, snippet);
            default -> "- [chat, conversation #%s, %s] %s".formatted(conversationId, metadata.get("role"), snippet);
        };
    }
}
