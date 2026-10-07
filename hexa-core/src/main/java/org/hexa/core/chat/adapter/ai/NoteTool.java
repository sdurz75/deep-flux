package org.hexa.core.chat.adapter.ai;

import org.hexa.core.chat.port.in.IChatToolkit;
import org.hexa.core.search.domain.DocumentTypes;
import org.hexa.core.search.port.in.IArchiveNotes;
import org.hexa.core.events.port.in.ISystemEvents;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Mutazione leggera: salva una nota manuale nell'archivio (le stesse di /search, ricercabili per significato). Solo creazione:
 * modificare o eliminare note resta a /search. Esiste solo con la ricerca semantica attiva, come {@link ArchiveSearchTool}.
 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
@Order(60)
public class NoteTool implements IChatToolkit {

    private final IArchiveNotes notes;
    private final ISystemEvents systemEvents;

    public NoteTool(IArchiveNotes notes, ISystemEvents systemEvents) {
        this.notes = notes;
        this.systemEvents = systemEvents;
    }

    @Tool(description = "Save a note in the user's archive (searchable later with searchArchive, editable in /search). Use it "
            + "ONLY when the user explicitly asks to save or remember something, e.g. a prompt, a trigger word or an idea. "
            + "Write the text exactly as the user wants it kept.")
    public String saveNote(
            @ToolParam(description = "Short title") String title,
            @ToolParam(description = "The note text") String text) {
        if (text == null || text.isBlank()) {
            return "Not saved: the note text is empty.";
        }
        if (text.strip().length() > DocumentTypes.MAX_CHARS) {
            return "Not saved: the text is too long (max " + DocumentTypes.MAX_CHARS + " characters). Shorten it and retry.";
        }
        try {
            notes.create(title == null ? "" : title.strip(), text.strip());
            return "Note saved in the archive.";
        } catch (RuntimeException e) {
            systemEvents.record("saveNote", e);
            return "Not saved: internal error (" + ISystemEvents.sanitize(e) + "). Tell the user it did not work.";
        }
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.notes";
    }
}
