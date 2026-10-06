package org.dual.replicate.core.search.adapter.in.web;

import java.util.List;
import java.util.Map;

import org.dual.replicate.core.search.adapter.out.vector.FakeEmbeddingModel;
import org.dual.replicate.core.search.adapter.out.vector.VectorDocumentRepository;
import org.dual.replicate.core.search.adapter.out.vector.VectorIndexer;
import org.dual.replicate.core.search.domain.DocumentTypes;
import org.springframework.ai.vectorstore.VectorStore;
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
// soglia 0: l'embedding finto non ha punteggi significativi (il default reale, 80, e' provato in SemanticSearchDefaultThresholdTest)
@SpringBootTest(properties = {"app.search.enabled=true", "app.search.similarity-threshold-percent=0"})
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
    private VectorStore vectorStore;
    @Autowired
    private VectorIndexer indexer;
    @Autowired
    private VectorDocumentRepository store;

    @BeforeEach
    void clean() {
        indexer.delete(store.list(null, 1, 1000).documents().stream().map(org.dual.replicate.core.search.domain.IndexedDocument::id).toList());
    }

    private void derived(String id, String type, long refId, String text) {
        indexer.upsertIfChanged(List.of(Document.builder().id(id).text(text).metadata(Map.of("type", type, "refId", refId)).build()));
    }

    private static org.springframework.ai.vectorstore.filter.Filter.Expression noteFilter() {
        return new org.springframework.ai.vectorstore.filter.Filter.Expression(
                org.springframework.ai.vectorstore.filter.Filter.ExpressionType.EQ,
                new org.springframework.ai.vectorstore.filter.Filter.Key("type"),
                new org.springframework.ai.vectorstore.filter.Filter.Value("note"));
    }

    private void derivedAt(String id, String type, long refId, String text, java.time.LocalDate day) {
        long millis = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        indexer.upsertIfChanged(List.of(Document.builder().id(id).text(text).metadata(Map.of("type", type, "refId", refId, "createdAt", millis)).build()));
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void pageRendersWithStatsFormsAndTheNavLink() throws Exception {
        derived("generation:1", "generation", 1, "un gatto sul divano");

        String page = mockMvc.perform(get("/search")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"search-stats\"").contains("id=\"search-results\"").contains("id=\"note-form\"")
                .contains("id=\"search-form\"").contains("name=\"from\"").contains("name=\"to\"").doesNotContain("search-list")
                .doesNotContain("name=\"topK\"").contains("un gatto sul divano").contains("href=\"/search\"")
                // "Nuova nota" e' un dialog modale (Pines): aperto da dialogOpen, focus confinato, form dentro il dialog
                .contains("x-data=\"{ dialogOpen: false }\"").contains("role=\"dialog\"").contains("aria-modal=\"true\"")
                .contains("x-trap.inert.noscroll=\"dialogOpen\"").contains("@note-saved.window");
        // Gli slot dell'host (app.search.host-fragment): filtro media e preferiti arrivano da fragments/app/search-host.
        assertThat(page).contains("id=\"search-media\"").contains("id=\"search-favourites\"");
        assertThat(page.indexOf("id=\"note-form\"")).isGreaterThan(page.indexOf("role=\"dialog\""));
    }

    @Test
    void semanticSearchReturnsScoredHitsAndHonoursTheTypeFilter() throws Exception {
        derived("generation:1", "generation", 1, "un gatto sul divano");
        derived("generation:2", "generation", 2, "auto in montagna");
        indexer.upsertIfChanged(List.of(Document.builder().id("note:a").text("appunto sul felino").metadata(Map.of("type", "note", "refId", 3L)).build()));

        String all = body(get("/search/results").param("q", "gatto").param("topK", "5"));
        String onlyNotes = body(get("/search/results").param("q", "gatto").param("type", "note"));

        assertThat(all).contains("un gatto sul divano").contains("appunto sul felino").contains("Somiglianza").contains("%");
        assertThat(onlyNotes).contains("appunto sul felino").doesNotContain("un gatto sul divano");
        assertThat(body(get("/search/results").param("q", "castello").param("type", "chat"))).contains("Nessun risultato");
    }

    @Test
    void invalidFiltersAreInlineErrorsAndABlankQueryBrowsesTheDocuments() throws Exception {
        derived("generation:1", "generation", 1, "un gatto sul divano");

        assertThat(body(get("/search/results").param("q", "gatto").param("threshold", "101"))).contains("tra 0 e 100");
        assertThat(body(get("/search/results").param("q", "gatto").param("from", "ieri"))).contains("data non e&#39; valida");
        assertThat(body(get("/search/results").param("from", "2026-02-01").param("to", "2026-01-01"))).contains("non puo&#39; essere dopo");
        assertThat(body(get("/search/results").param("q", "castello").param("threshold", "100"))).contains("Nessun risultato").doesNotContain("<li");
        // senza testo: si sfoglia (nessun punteggio), non e' una ricerca vuota
        assertThat(body(get("/search/results").param("q", "  "))).contains("un gatto sul divano").doesNotContain("Somiglianza");
        assertThat(body(get("/search/results").param("type", "chat"))).contains("Nessun documento").doesNotContain("<li");
    }

    @Test
    void theSingleListIsPaginatedBothBrowsingAndRankedAndPageLinksKeepTheFilters() throws Exception {
        // type "note": la riconciliazione di fondo (ArchiveIndexService, attiva in questo contesto) cancella i derivati finti ("chat") che non trova nel DB
        for (int i = 0; i < 25; i++) {
            derivedAt("note:n" + i, "note", i, "castello numero " + i, java.time.LocalDate.of(2026, 1, 1).plusDays(i));
        }
        derived("generation:1", "generation", 1, "altro tipo");

        String first = body(get("/search/results").param("type", "note"));
        String second = body(get("/search/results").param("type", "note").param("page", "2"));
        String ranked = body(get("/search/results").param("q", "castello").param("type", "note").param("from", "2026-01-01"));
        String rankedSecond = body(get("/search/results").param("q", "castello").param("type", "note").param("from", "2026-01-01").param("page", "2"));

        assertThat(first).contains("25 risultati").contains("castello numero 24").doesNotContain("altro tipo")
                .contains("/search/results?q=&amp;type=note&amp;from=&amp;to=&amp;threshold=0&amp;media=all&amp;favourites=false&amp;tag=&amp;page=2");
        assertThat(second).contains("castello numero").doesNotContain("page=3");
        assertThat(ranked).contains("Somiglianza").contains("25 risultati")
                .contains("q=castello&amp;type=note&amp;from=2026-01-01&amp;to=&amp;threshold=0&amp;media=all&amp;favourites=false&amp;tag=&amp;page=2");
        assertThat(rankedSecond).contains("castello numero").doesNotContain("page=3");
        mockMvc.perform(get("/search/list/chat")).andExpect(status().is4xxClientError());
    }

    private void artifact(String id, long refId, String prompt, String kind, boolean favourite, List<String> files) {
        indexer.upsertIfChanged(List.of(Document.builder().id(id).text(prompt + DocumentTypes.TAGS_SEPARATOR + "tag-interno-xyz")
                .metadata(Map.of("type", "generation", "refId", refId, "kind", kind, "model", "owner/modello-x", "favourite", favourite,
                        "files", files, "favouriteFiles", favourite ? files : List.of()))
                .build()));
    }

    @Test
    void generationHitsShowTheirThumbnailsAndBadgesButNotTheIndexTags() throws Exception {
        artifact("generation:901", 901, "una volpe nella neve", "IMAGE", true, List.of("volpe-a.png", "volpe-b.png"));
        artifact("generation:902", 902, "una volpe che corre", "VIDEO", false, List.of("volpe.mp4"));

        String html = body(get("/search/results").param("q", "volpe"));

        assertThat(html).contains("/images/volpe-a.png").contains("/images/volpe-b.png").contains("<video").contains("/images/volpe.mp4")
                .contains("href=\"/generations/901\"").contains("owner/modello-x").contains("una volpe nella neve")
                // la riga mostra il prompt; le tag d'indice restano solo nel <pre> "Dettagli" (vista admin)
                .contains("Immagine").contains("Video");
        String preview = html.substring(0, html.indexOf("<details"));
        assertThat(preview).doesNotContain("tag-interno-xyz");
    }

    @Test
    void tagFilterNarrowsTheListAndTheRankingKeepsTheTagInThePageLinksAndShowsChips() throws Exception {
        indexer.upsertIfChanged(List.of(
                Document.builder().id("generation:921").text("una barca").metadata(Map.of("type", "generation", "refId", 921L, "tags", List.of("mare", "estate"))).build(),
                Document.builder().id("imported:922").text("una barca ferma").metadata(Map.of("type", "imported", "refId", 922L, "tags", List.of("mare"))).build(),
                Document.builder().id("generation:923").text("una barca in porto").metadata(Map.of("type", "generation", "refId", 923L, "tags", List.of("porto"))).build()));

        String ranked = body(get("/search/results").param("q", "barca").param("tag", "  Mare "));
        String browsed = body(get("/search/results").param("tag", "estate"));
        String none = body(get("/search/results").param("tag", "mar"));
        String page = mockMvc.perform(get("/search").param("tag", "estate")).andReturn().getResponse().getContentAsString();

        assertThat(ranked).contains("una barca").contains("una barca ferma").doesNotContain("in porto")
                .contains("href=\"/search?tag=mare\"").contains("href=\"/search?tag=estate\"");
        assertThat(browsed).contains(">una barca<").doesNotContain("ferma").doesNotContain("in porto");
        assertThat(none).contains("Nessun documento");
        assertThat(page).contains("name=\"tag\"").contains("value=\"estate\"").contains("id=\"known-tags\"").contains("value=\"porto\"");
        assertThat(ranked).doesNotContain("/search/results?"); // un solo risultato per pagina: niente paginazione
    }

    @Test
    void mediaAndFavouriteFiltersNarrowToGenerationsAndKeepTheirValueInThePageLinks() throws Exception {
        artifact("generation:911", 911, "un faro sul mare", "IMAGE", true, List.of("faro.png"));
        artifact("generation:912", 912, "un faro di notte", "VIDEO", false, List.of("faro.mp4"));
        artifact("generation:913", 913, "un faro in tempesta", "IMAGE", false, List.of("faro2.png"));
        indexer.upsertIfChanged(List.of(Document.builder().id("note:faro").text("appunto sul faro").metadata(Map.of("type", "note", "refId", 5L)).build()));

        String images = body(get("/search/results").param("q", "faro").param("media", "image"));
        String videos = body(get("/search/results").param("q", "faro").param("media", "video"));
        String favourites = body(get("/search/results").param("q", "faro").param("favourites", "true"));
        String browseVideos = body(get("/search/results").param("media", "video"));

        assertThat(images).contains("un faro sul mare").contains("un faro in tempesta").doesNotContain("un faro di notte").doesNotContain("appunto sul faro");
        assertThat(videos).contains("un faro di notte").doesNotContain("un faro sul mare").doesNotContain("appunto sul faro");
        assertThat(favourites).contains("un faro sul mare").doesNotContain("un faro in tempesta").doesNotContain("appunto sul faro");
        assertThat(browseVideos).contains("un faro di notte").doesNotContain("un faro sul mare");
        assertThat(body(get("/search/results").param("q", "faro").param("media", "video").param("favourites", "true"))).contains("Nessun risultato");
        // la form espone i due controlli
        assertThat(mockMvc.perform(get("/search")).andReturn().getResponse().getContentAsString()).contains("name=\"media\"").contains("name=\"favourites\"");
    }

    @Test
    void thePeriodRestrictsByCreationDateWithInclusiveEnds() throws Exception {
        derivedAt("note:n1", "note", 1, "messaggio di gennaio", java.time.LocalDate.of(2026, 1, 10));
        derivedAt("note:n2", "note", 2, "messaggio di febbraio", java.time.LocalDate.of(2026, 2, 10));
        derivedAt("note:n3", "note", 3, "messaggio di marzo", java.time.LocalDate.of(2026, 3, 10));

        String february = body(get("/search/results").param("from", "2026-02-10").param("to", "2026-02-10"));
        String sinceFebruary = body(get("/search/results").param("q", "castello").param("from", "2026-02-01"));
        String untilFebruary = body(get("/search/results").param("q", "castello").param("to", "2026-02-28"));

        assertThat(february).contains("messaggio di febbraio").doesNotContain("di gennaio").doesNotContain("di marzo");
        assertThat(sinceFebruary).contains("di febbraio").contains("di marzo").doesNotContain("di gennaio");
        assertThat(untilFebruary).contains("di gennaio").contains("di febbraio").doesNotContain("di marzo");
    }

    @Test
    void createEditAndDeleteANote() throws Exception {
        var createdResponse = mockMvc.perform(post("/search/notes").param("text", "il mio castello con il drago").param("title", "Idea")
                .header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(createdResponse.getHeader("HX-Trigger")).contains("note-saved"); // chiude il dialog
        String created = createdResponse.getContentAsString();

        // la risposta porta solo le statistiche: la lista si ricarica da sola (search-form ascolta note-saved) coi filtri correnti
        assertThat(created).contains("id=\"search-stats\"").doesNotContain("<li");
        assertThat(body(get("/search/results").param("type", "note"))).contains("il mio castello con il drago").contains("Idea");
        org.dual.replicate.core.search.domain.IndexedDocument note = store.list(noteFilter(), 1, 10).documents().get(0);
        assertThat(note.id()).startsWith("note:");
        assertThat(note.metadata()).containsKey("createdAt");
        long createdAt = ((Number) note.metadata().get("createdAt")).longValue();
        assertThat(vectorStore.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder().query("drago").topK(1).build()))
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
        assertThat(((Number) store.find(note.id()).orElseThrow().metadata().get("createdAt")).longValue()).isEqualTo(createdAt);

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
        assertThat(store.list(noteFilter(), 1, 10).documents()).isEmpty();
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
        String list = body(get("/search/results").param("type", "generation"));
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
    void reindexReturnsTheStatsFragment() throws Exception {
        assertThat(body(post("/search/reindex"))).contains("id=\"search-stats\"").doesNotContain("hx-swap-oob");
    }
}
