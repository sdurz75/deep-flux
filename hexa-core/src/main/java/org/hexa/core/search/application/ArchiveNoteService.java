package org.hexa.core.search.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hexa.core.search.domain.DocumentTypes;
import org.hexa.core.search.domain.IndexedDocument;
import org.hexa.core.search.domain.SearchableDocument;
import org.hexa.core.search.port.in.IArchiveNotes;
import org.hexa.core.search.port.out.IVectorIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveNoteService implements IArchiveNotes {

    private final IVectorIndex index;

    public ArchiveNoteService(IVectorIndex index) {
        this.index = index;
    }

    @Override
    public String create(String title, String text) {
        long now = System.currentTimeMillis();
        String id = DocumentTypes.newNoteId(UUID.randomUUID().toString());
        index.upsertIfChanged(List.of(note(id, now, now, title, text)));
        return id;
    }

    @Override
    public void update(String id, String title, String text) {
        requireNote(id);
        IndexedDocument existing = index.find(id).orElseThrow(() -> new IllegalArgumentException("Nota inesistente: " + id));
        // La data di creazione non cambia con la modifica (le note piu' vecchie non l'hanno ancora: refId e' lo stesso istante).
        index.upsertIfChanged(List.of(note(id, existing.refId(), existing.createdAt().toEpochMilli(), title, text)));
    }

    @Override
    public void delete(String id) {
        requireNote(id);
        index.delete(List.of(id));
    }

    private static void requireNote(String id) {
        if (!DocumentTypes.isNote(id)) {
            throw new IllegalArgumentException("Solo le note sono modificabili: " + id);
        }
    }

    private static SearchableDocument note(String id, long refId, long createdAt, String title, String text) {
        Map<String, Object> extra = title == null || title.isBlank() ? Map.of() : Map.of("title", title.strip());
        return SearchableDocument.of(id, DocumentTypes.NOTE, refId, null, Instant.ofEpochMilli(createdAt), text.strip(), extra);
    }
}
