package org.dual.replicate.app.search.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Un documento da indicizzare: id (unico nell'indice, es. {@code generation:12}), testo e metadata. Servono almeno {@code type}
 * (stringa) e {@code refId} (numero); {@link #of} costruisce i metadata standard.
 */
public record SearchableDocument(String id, String text, Map<String, Object> metadata) {

    public static SearchableDocument of(String id, String type, long refId, Long conversationId, Instant createdAt, String text,
                                        Map<String, Object> extra) {
        Map<String, Object> metadata = new HashMap<>(extra);
        metadata.put("type", type);
        metadata.put("refId", refId);
        if (createdAt != null) {
            metadata.put("createdAt", createdAt.toEpochMilli());
        }
        if (conversationId != null) {
            metadata.put("conversationId", conversationId);
        }
        return new SearchableDocument(id, text, metadata);
    }
}
