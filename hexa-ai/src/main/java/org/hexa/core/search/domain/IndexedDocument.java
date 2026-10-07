package org.hexa.core.search.domain;

import java.time.Instant;
import java.util.Map;

/** Un documento cosi' come sta nell'indice, per l'interfaccia di amministrazione (senza il vettore). */
public record IndexedDocument(String id, String type, Long refId, Long conversationId, String content,
                              Map<String, Object> metadata, String model, String hash, Instant updatedAt, int dimensions) {

    /** Il contenuto senza le tag d'indice ({@link DocumentTypes#TAGS_SEPARATOR}): e' quello che si mostra all'utente. */
    public String visibleContent() {
        return DocumentTypes.visibleText(content);
    }

    /** Quando e' stato creato il contenuto (metadata {@code createdAt}); in mancanza, l'ultima indicizzazione. */
    public Instant createdAt() {
        return metadata.get("createdAt") instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : updatedAt;
    }
}
