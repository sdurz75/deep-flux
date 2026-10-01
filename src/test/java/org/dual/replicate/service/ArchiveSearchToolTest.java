package org.dual.replicate.service;

import java.util.List;
import java.util.Map;

import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveSearchToolTest {

    private final VectorStore store = mock(VectorStore.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ArchiveSearchTool tool = new ArchiveSearchTool(store, systemEvents, 3);

    private static Document hit(String id, String text, Map<String, Object> metadata) {
        return Document.builder().id(id).text(text).metadata(metadata).score(0.9).build();
    }

    @Test
    void describesEachKindOfHitWithItsIdAndLink() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                hit("generation:12", "un felino sul divano", Map.of("type", "generation", "refId", 12)),
                hit("chatmessage:5", "vorrei un castello", Map.of("type", "chat", "refId", 5, "conversationId", 3, "role", "USER")),
                hit("conversation:3", "Il castello del drago", Map.of("type", "conversation", "refId", 3))));

        String result = tool.searchArchive("gatto", null);

        assertThat(result).contains("[generation #12] (/generations/12) un felino sul divano")
                .contains("[chat, conversation #3, USER] vorrei un castello")
                .contains("[conversation #3] Il castello del drago");
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(3);
        assertThat(request.getValue().getQuery()).isEqualTo("gatto");
        assertThat(request.getValue().hasFilterExpression()).isFalse();
    }

    @Test
    void aTypeFilterIsAppliedAndAnInvalidOneIsRefusedWithoutSearching() {
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertThat(tool.searchArchive("gatto", "Generation")).isEqualTo("Nessun risultato nell'archivio.");
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(request.capture());
        assertThat(request.getValue().getFilterExpression().toString()).contains("type").contains("generation");

        org.mockito.Mockito.clearInvocations(store);
        assertThat(tool.searchArchive("gatto", "immagini")).contains("Tipo non valido");
        verify(store, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void aStoreFailureIsRecordedAndReportedToTheModelInsteadOfBreakingTheTurn() {
        RuntimeException failure = new IllegalStateException("modello non caricato");
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(failure);

        String result = tool.searchArchive("gatto", null);

        assertThat(result).contains("non disponibile").contains("modello non caricato");
        verify(systemEvents).record("searchArchive", failure);
    }
}
