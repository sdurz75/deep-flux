package org.hexa.core.chat.adapter.ai;

import org.hexa.core.search.domain.DocumentTypes;
import org.hexa.core.search.port.in.IArchiveNotes;
import org.hexa.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoteToolTest {

    private final IArchiveNotes notes = mock(IArchiveNotes.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final NoteTool noteTool = new NoteTool(notes, systemEvents);

    @Test
    void saveNoteStoresTheTrimmedTextAndRejectsEmptyOrTooLongOnes() {
        assertThat(noteTool.saveNote(" Idea ", "  un castello  ")).isEqualTo("Note saved in the archive.");
        verify(notes).create("Idea", "un castello");

        assertThat(noteTool.saveNote("x", "   ")).contains("empty");
        assertThat(noteTool.saveNote("x", "a".repeat(DocumentTypes.MAX_CHARS + 1))).contains("too long");
        verify(notes, org.mockito.Mockito.times(1)).create(anyString(), anyString());
    }

    @Test
    void aFailureOnSaveIsRecordedAndReturnedAsText() {
        RuntimeException boom = new IllegalStateException("indice giu'");
        when(notes.create(anyString(), anyString())).thenThrow(boom);

        assertThat(noteTool.saveNote("t", "testo")).contains("Not saved", "indice giu'");
        verify(systemEvents).record("saveNote", boom);
    }
}
