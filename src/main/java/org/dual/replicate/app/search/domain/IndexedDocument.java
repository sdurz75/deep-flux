package org.dual.replicate.app.search.domain;

import java.time.Instant;
import java.util.Map;

/** Un documento cosi' come sta nell'indice, per l'interfaccia di amministrazione (senza il vettore). */
public record IndexedDocument(String id, String type, Long refId, Long conversationId, String content,
                              Map<String, Object> metadata, String model, String hash, Instant updatedAt, int dimensions) {

    /** Quando e' stato creato il contenuto (metadata {@code createdAt}); in mancanza, l'ultima indicizzazione. */
    public Instant createdAt() {
        return metadata.get("createdAt") instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : updatedAt;
    }
}
