package org.dual.replicate.core.chat.adapter.ai;

import org.dual.replicate.core.chat.port.in.IChatToolkit;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.search.domain.DocumentFilter;
import org.dual.replicate.core.search.domain.IndexedDocument;
import org.dual.replicate.core.kernel.Tags;
import org.dual.replicate.core.search.domain.DocumentTypes;
import org.dual.replicate.core.search.domain.ScoredDocument;
import org.dual.replicate.core.search.port.in.IArchiveSearch;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tool del modello di /deep-chat: ricerca SEMANTICA nell'archivio dell'utente (prompt delle immagini/video generati, immagini
 * importate, note, messaggi delle conversazioni passate, titoli) sull'archivio ({@link IArchiveSearch}), con gli stessi filtri di
 * /search (tipo, tag, media, preferiti, periodo). Senza testo ma con almeno un filtro elenca i documenti piu' recenti. Come
 * {@link WebSearchTool}, un guasto non rompe il turno: e' registrato e il modello riceve un testo d'errore.
 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
@Order(30)
public class ArchiveSearchTool implements IChatToolkit {

    private static final int SNIPPET = 300;

    private final IArchiveSearch search;
    private final ISystemEvents systemEvents;
    private final int topK;

    public ArchiveSearchTool(IArchiveSearch search, ISystemEvents systemEvents, @Value("${app.search.top-k:5}") int topK) {
        this.search = search;
        this.systemEvents = systemEvents;
        this.topK = topK;
    }

    @Tool(description = "Search the user's own archive: prompts of images and videos generated in the past, imported images (their "
            + "description), notes, messages of earlier conversations and conversation titles. With a query it ranks by meaning "
            + "(not exact words): use it when the user refers to something made or discussed before (\"the cat picture from last "
            + "week\", \"that prompt about the castle\") or asks for something similar to past work. With an EMPTY query and at "
            + "least one filter it lists the most recent matching items (\"my latest favourite videos\", \"everything tagged "
            + "holiday\"). Items may carry user tags (labels set by hand; the tag filter is exact). Returns type, id, tags and text; "
            + "generations open at /generations/{id}, imported images at /import/{id}.")
    public String searchArchive(
            @ToolParam(description = "What to look for, in natural language (Italian or English); empty to just filter", required = false) String query,
            @ToolParam(description = "Optional filter: generation, imported, note, chat or conversation", required = false) String type,
            @ToolParam(description = "Optional user tag (exact, case-insensitive) the items must carry", required = false) String tag,
            @ToolParam(description = "Optional: true to keep only items with a starred file", required = false) Boolean favouritesOnly,
            @ToolParam(description = "Optional media filter: image or video", required = false) String media,
            @ToolParam(description = "Optional: only items created on or after this date (YYYY-MM-DD)", required = false) String since,
            @ToolParam(description = "Optional: only items created on or before this date (YYYY-MM-DD)", required = false) String until) {
        try {
            String wantedType = null;
            if (!blank(type)) {
                wantedType = type.strip().toLowerCase(Locale.ROOT);
                List<String> types = search.types();
                if (!types.contains(wantedType)) {
                    return "Invalid type (" + type + "): use " + String.join(", ", types) + ", or omit the filter.";
                }
            }
            String wantedKind = null;
            if (!blank(media)) {
                wantedKind = switch (media.strip().toLowerCase(Locale.ROOT)) {
                    case "image" -> "IMAGE";
                    case "video" -> "VIDEO";
                    default -> null;
                };
                if (wantedKind == null) {
                    return "Invalid media (" + media + "): use image or video, or omit the filter.";
                }
            }
            Instant from;
            Instant to;
            try {
                from = blank(since) ? null : LocalDate.parse(since.strip()).atStartOfDay(ZoneId.systemDefault()).toInstant();
                to = blank(until) ? null : LocalDate.parse(until.strip()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1);
            } catch (DateTimeParseException e) {
                return "Invalid date: use the YYYY-MM-DD format.";
            }
            String wantedTag = Tags.normalize(tag);
            boolean favourites = Boolean.TRUE.equals(favouritesOnly);
            DocumentFilter filter = new DocumentFilter(wantedType, from, to, wantedKind, favourites, wantedTag.isEmpty() ? null : wantedTag);
            if (blank(query)) {
                if (wantedType == null && wantedKind == null && from == null && to == null && !favourites && wantedTag.isEmpty()) {
                    return "Give a query, or at least one filter (type, tag, media, favourites, dates).";
                }
                // Nessuna somiglianza da calcolare: i piu' recenti che passano i filtri.
                List<IndexedDocument> listed = search.list(filter, 0, topK).content();
                return listed.isEmpty() ? "No results in the archive."
                        : listed.stream().map(d -> describe(d.id(), d.metadata(), d.content())).collect(Collectors.joining("\n"));
            }
            List<ScoredDocument> found = search.search(query, filter, 0.0, topK);
            if (found.isEmpty()) {
                return "No results in the archive.";
            }
            return found.stream().map(this::describe).collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            systemEvents.record("searchArchive", e);
            return "The archive search is not available right now (" + ISystemEvents.sanitize(e)
                    + "). Answer without it, telling the user the search did not work.";
        }
    }

    private String describe(ScoredDocument result) {
        return describe(result.document().id(), result.document().metadata(), result.document().text());
    }

    private String describe(String id, Map<String, Object> metadata, String content) {
        String type = String.valueOf(metadata.get("type"));
        String text = DocumentTypes.visibleText(content).replaceAll("\\s+", " ");
        String snippet = text.length() > SNIPPET ? text.substring(0, SNIPPET) + "…" : text;
        String tags = metadata.get("tags") instanceof Collection<?> list && !list.isEmpty()
                ? " [tags: " + list.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "]" : "";
        if (DocumentTypes.NOTE.equals(type)) {
            // Per una nota refId e' l'istante di creazione, non un id utile: la si identifica col titolo (e l'id dell'indice).
            return "- [note%s] (%s) %s".formatted(metadata.get("title") == null ? "" : " \"" + metadata.get("title") + "\"", id, snippet);
        }
        // La citazione (etichetta e link) e' della sorgente che possiede il tipo; i tag, quando ci sono, seguono.
        return "- %s%s %s".formatted(search.citation(type, metadata), tags, snippet);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.archive";
    }
}
