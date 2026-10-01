package org.dual.replicate.service;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.IArchiveSearch;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveSearchToolTest {

    private final IArchiveSearch search = mock(IArchiveSearch.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ArchiveSearchTool tool = new ArchiveSearchTool(search, systemEvents, 3);

    private static ScoredDocument hit(String id, String text, Map<String, Object> metadata) {
        return new ScoredDocument(new SearchableDocument(id, text, metadata), 0.9);
    }

    @Test
    void describesEachKindOfHitWithItsIdAndLink() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of(
                hit("generation:12", "un felino sul divano", Map.of("type", "generation", "refId", 12)),
                hit("chatmessage:5", "vorrei un castello", Map.of("type", "chat", "refId", 5, "conversationId", 3, "role", "USER")),
                hit("conversation:3", "Il castello del drago", Map.of("type", "conversation", "refId", 3))));

        String result = tool.searchArchive("gatto", null);

        assertThat(result).contains("[generation #12] (/generations/12) un felino sul divano")
                .contains("[chat, conversation #3, USER] vorrei un castello")
                .contains("[conversation #3] Il castello del drago");
        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(org.mockito.ArgumentMatchers.eq("gatto"), filter.capture(), anyDouble(), org.mockito.ArgumentMatchers.eq(3));
        assertThat(filter.getValue().type()).isNull();
    }

    @Test
    void aTypeFilterIsAppliedAndAnInvalidOneIsRefusedWithoutSearching() {
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenReturn(List.of());

        assertThat(tool.searchArchive("gatto", "Generation")).isEqualTo("Nessun risultato nell'archivio.");
        ArgumentCaptor<DocumentFilter> filter = ArgumentCaptor.forClass(DocumentFilter.class);
        verify(search).search(anyString(), filter.capture(), anyDouble(), anyInt());
        assertThat(filter.getValue().type()).isEqualTo("generation");

        org.mockito.Mockito.clearInvocations(search);
        assertThat(tool.searchArchive("gatto", "immagini")).contains("Tipo non valido");
        verify(search, never()).search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt());
    }

    @Test
    void aStoreFailureIsRecordedAndReportedToTheModelInsteadOfBreakingTheTurn() {
        RuntimeException failure = new IllegalStateException("modello non caricato");
        when(search.search(anyString(), any(DocumentFilter.class), anyDouble(), anyInt())).thenThrow(failure);

        String result = tool.searchArchive("gatto", null);

        assertThat(result).contains("non disponibile").contains("modello non caricato");
        verify(systemEvents).record("searchArchive", failure);
    }
}
