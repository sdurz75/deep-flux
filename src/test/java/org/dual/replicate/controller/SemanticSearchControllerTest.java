package org.dual.replicate.controller;

import java.util.List;
import java.util.Map;

import org.dual.replicate.search.vector.FakeEmbeddingModel;
import org.dual.replicate.search.vector.H2VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /search con la ricerca semantica ATTIVA ma un embedding finto (mai il modello vero): pagina e fragment, ricerca con punteggi,
 * note modificabili, derivati in sola lettura.
 */
@SpringBootTest(properties = "app.search.enabled=true")
@AutoConfigureMockMvc
@Import(SemanticSearchControllerTest.FakeEmbedding.class)
class SemanticSearchControllerTest {

    @TestConfiguration
    static class FakeEmbedding {
        @Bean
        @Primary
        EmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private H2VectorStore store;

    @BeforeEach
    void clean() {
        store.delete(store.list(null, 1, 1000).documents().stream().map(H2VectorStore.StoredDocument::id).toList());
    }

    private void derived(String id, String type, long refId, String text) {
        store.add(List.of(Document.builder().id(id).text(text).metadata(Map.of("type", type, "refId", refId)).build()));
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void pageRendersWithStatsFormsAndTheNavLink() throws Exception {
        derived("generation:1", "generation", 1, "un gatto sul divano");

        String page = mockMvc.perform(get("/search")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"search-stats\"").contains("id=\"search-results\"").contains("id=\"note-form\"")
                .contains("id=\"search-list\"").contains("un gatto sul divano").contains("href=\"/search\"")
                // "Nuova nota" e' un dialog modale (Pines): aperto da dialogOpen, focus confinato, form dentro il dialog
                .contains("x-data=\"{ dialogOpen: false }\"").contains("role=\"dialog\"").contains("aria-modal=\"true\"")
                .contains("x-trap.inert.noscroll=\"dialogOpen\"").contains("@note-saved.window");
        assertThat(page.indexOf("id=\"note-form\"")).isGreaterThan(page.indexOf("role=\"dialog\""));
    }

    @Test
    void semanticSearchReturnsScoredHitsAndHonoursTheTypeFilter() throws Exception {
        derived("generation:1", "generation", 1, "un gatto sul divano");
        derived("generation:2", "generation", 2, "auto in montagna");
        store.add(List.of(Document.builder().id("note:a").text("appunto sul felino").metadata(Map.of("type", "note", "refId", 3L)).build()));

        String all = body(get("/search/results").param("q", "gatto").param("topK", "5"));
        String onlyNotes = body(get("/search/results").param("q", "gatto").param("type", "note"));

        assertThat(all).contains("un gatto sul divano").contains("appunto sul felino").contains("Somiglianza").contains("%");
        assertThat(onlyNotes).contains("appunto sul felino").doesNotContain("un gatto sul divano");
        assertThat(body(get("/search/results").param("q", "castello").param("type", "chat"))).contains("Nessun risultato");
    }

    @Test
    void anOutOfRangeTopKIsAnInlineErrorAndABlankQueryShowsNothing() throws Exception {
        assertThat(body(get("/search/results").param("q", "gatto").param("topK", "500"))).contains("tra 1 e 50");
        assertThat(body(get("/search/results").param("q", "gatto").param("threshold", "101"))).contains("tra 0 e 100");
        assertThat(body(get("/search/results").param("q", "gatto").param("threshold", "100"))).doesNotContain("<li");
        assertThat(body(get("/search/results").param("q", "  "))).doesNotContain("Nessun risultato").doesNotContain("<li");
    }

    @Test
    void createEditAndDeleteANote() throws Exception {
        var createdResponse = mockMvc.perform(post("/search/notes").param("text", "il mio castello con il drago").param("title", "Idea")
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(createdResponse.getHeader("HX-Trigger")).contains("note-saved"); // chiude il dialog
        String created = createdResponse.getContentAsString();

        assertThat(created).contains("il mio castello con il drago").contains("Idea").contains("id=\"search-stats\"");
        H2VectorStore.StoredDocument note = store.list("note", 1, 10).documents().get(0);
        assertThat(note.id()).startsWith("note:");
        assertThat(store.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder().query("drago").topK(1).build()))
                .extracting(Document::getId).containsExactly(note.id());

        String edit = body(get("/search/notes/{id}/edit", note.id()));
        assertThat(edit).contains("id=\"note-form\"").contains("<textarea").contains("il mio castello con il drago").contains("Idea");

        var updatedResponse = mockMvc.perform(post("/search/notes/{id}", note.id()).param("text", "una montagna innevata").param("title", ""))
                .andReturn().getResponse();
        assertThat(updatedResponse.getHeader("HX-Trigger")).contains("note-saved");
        String updated = updatedResponse.getContentAsString();
        assertThat(updated).contains("una montagna innevata").doesNotContain("il mio castello");
        assertThat(store.find(note.id()).orElseThrow().content()).isEqualTo("una montagna innevata");
        assertThat(store.find(note.id()).orElseThrow().metadata()).doesNotContainKey("title");

        String deleted = body(delete("/search/notes/{id}", note.id()));
        assertThat(deleted).contains("id=\"search-stats\"").doesNotContain("<li");
        assertThat(store.find(note.id())).isEmpty();
    }

    @Test
    void invalidNotesAreRejectedInlineAndCreateNothing() throws Exception {
        var invalid = mockMvc.perform(post("/search/notes").param("text", "   ").header("HX-Request", "true")).andReturn().getResponse();
        assertThat(invalid.getContentAsString()).contains("obbligatorio");
        assertThat(invalid.getHeader("HX-Trigger")).isNull(); // il dialog resta aperto col messaggio
        assertThat(invalid.getHeader("HX-Retarget")).isEqualTo("#note-form");
        assertThat(body(post("/search/notes").param("text", "x".repeat(2000)))).contains("supera 1800");
        assertThat(store.list("note", 1, 10).documents()).isEmpty();
    }

    @Test
    void derivedDocumentsAreReadOnly() throws Exception {
        derived("generation:9", "generation", 9, "un gatto");

        mockMvc.perform(get("/search/notes/{id}/edit", "generation:9")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/search/notes/{id}", "generation:9").param("text", "hack")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(delete("/search/notes/{id}", "generation:9")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/search/notes/{id}/edit", "note:inesistente")).andExpect(status().isNotFound());

        assertThat(store.find("generation:9").orElseThrow().content()).isEqualTo("un gatto");
        // e nella lista i derivati non hanno Modifica/Elimina, solo Ri-embedda
        String list = body(get("/search/list/generation"));
        assertThat(list).contains("un gatto").contains("Ri-embedda").doesNotContain("Elimina").doesNotContain("Modifica");
    }

    @Test
    void reembedRecomputesAnyDocumentAndUnknownIdsAre404() throws Exception {
        derived("generation:1", "generation", 1, "un gatto");
        int before = ((FakeEmbeddingModel) fake()).embedded.get();

        String row = body(post("/search/docs/{id}/reembed", "generation:1"));

        assertThat(row).contains("un gatto");
        assertThat(((FakeEmbeddingModel) fake()).embedded.get()).isGreaterThan(before);
        mockMvc.perform(post("/search/docs/{id}/reembed", "generation:404")).andExpect(status().isNotFound());
    }

    @Autowired
    private EmbeddingModel embeddingModel;

    private EmbeddingModel fake() {
        return embeddingModel;
    }

    @Test
    void listIsPaginatedPerTypeAndAnUnknownTypeIs400() throws Exception {
        for (int i = 0; i < 25; i++) {
            derived("chatmessage:" + i, "chat", i, "messaggio numero " + i);
        }

        String first = body(get("/search/list/chat"));
        String second = body(get("/search/list/chat").param("page", "2"));

        assertThat(first).contains("/search/list/chat?page=2");
        assertThat(second).contains("messaggio numero").doesNotContain("/search/list/chat?page=3");
        mockMvc.perform(get("/search/list/bogus")).andExpect(status().isBadRequest());
    }

    @Test
    void reindexReturnsTheStatsFragment() throws Exception {
        assertThat(body(post("/search/reindex"))).contains("id=\"search-stats\"").doesNotContain("hx-swap-oob");
    }
}
