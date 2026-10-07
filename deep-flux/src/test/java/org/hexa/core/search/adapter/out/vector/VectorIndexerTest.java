package org.hexa.core.search.adapter.out.vector;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgVectorStore + VectorIndexer + VectorDocumentRepository contro il Postgres+pgvector di test (schema Flyway V1); embedding finto:
 * nessun modello vero.
 */
@SpringBootTest
class VectorIndexerTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private FakeEmbeddingModel embedding;
    private VectorStore vectorStore;
    private VectorDocumentRepository repository;
    private VectorIndexer indexer;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from vector_store").update();
        embedding = new FakeEmbeddingModel();
        vectorStore = SemanticSearchConfig.pgVectorStore(jdbcTemplate, embedding);
        repository = new VectorDocumentRepository(jdbc, objectMapper);
        indexer = new VectorIndexer(vectorStore, repository, "modello-a");
    }

    private void add(Document... documents) {
        indexer.upsertIfChanged(List.of(documents));
    }

    private List<Document> search(SearchRequest request) {
        return vectorStore.similaritySearch(request);
    }

    private static Document doc(String id, String text, String type, long refId) {
        return Document.builder().id(id).text(text).metadata(Map.of("type", type, "refId", refId)).build();
    }

    private static Filter.Expression typeIs(String type) {
        return new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("type"), new Filter.Value(type));
    }

    @Test
    void ranksBySemanticSimilarityAndReturnsScoresInOrder() {
        add(doc("generation:1", "un felino sul divano", "generation", 1),
                doc("generation:2", "auto sportiva in montagna", "generation", 2),
                doc("generation:3", "castello con drago", "generation", 3));

        List<Document> found = search(SearchRequest.builder().query("gatto").topK(3).build());

        assertThat(found).extracting(Document::getId).first().isEqualTo("generation:1");
        assertThat(found).hasSize(3);
        assertThat(found.get(0).getScore()).isGreaterThan(found.get(1).getScore());
        assertThat(found.get(0).getMetadata()).containsEntry("type", "generation");
    }

    @Test
    void topKAndSimilarityThresholdAreRespected() {
        add(doc("a", "gatto", "generation", 1), doc("b", "auto", "generation", 2), doc("c", "mare", "generation", 3));

        assertThat(search(SearchRequest.builder().query("gatto").topK(2).build())).hasSize(2);
        assertThat(search(SearchRequest.builder().query("gatto").topK(5).similarityThreshold(0.9).build()))
                .extracting(Document::getId).containsExactly("a");
    }

    @Test
    void filtersRestrictTheCandidates() {
        add(doc("generation:1", "gatto", "generation", 1), doc("chatmessage:1", "gatto", "chat", 1),
                Document.builder().id("chatmessage:2").text("gatto grigio").metadata(Map.of("type", "chat", "refId", 2L, "conversationId", 9L)).build());

        assertThat(search(SearchRequest.builder().query("gatto").topK(5).filterExpression(typeIs("chat")).build()))
                .extracting(Document::getId).containsExactlyInAnyOrder("chatmessage:1", "chatmessage:2");
        Filter.Expression inConversation = new Filter.Expression(Filter.ExpressionType.AND, typeIs("chat"),
                new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("conversationId"), new Filter.Value(9)));
        assertThat(search(SearchRequest.builder().query("gatto").topK(5).filterExpression(inConversation).build()))
                .extracting(Document::getId).containsExactly("chatmessage:2");
        // GT/GTE/LT/LTE sono supportati (periodo di /search): refId > 1 esclude il primo
        assertThat(search(SearchRequest.builder().query("gatto").topK(5)
                .filterExpression(new Filter.Expression(Filter.ExpressionType.GT, new Filter.Key("refId"), new Filter.Value(1))).build()))
                .extracting(Document::getId).doesNotContain("chatmessage:1");
    }

    @Test
    void unchangedDocumentsAreNotReEmbedded() {
        Document d = doc("generation:1", "gatto", "generation", 1);
        add(d);
        int afterFirst = embedding.embedded.get();

        add(d);
        assertThat(embedding.embedded.get()).isEqualTo(afterFirst);

        add(doc("generation:1", "gatto nero", "generation", 1)); // testo cambiato
        assertThat(embedding.embedded.get()).isGreaterThan(afterFirst);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void aDifferentEmbeddingModelRecomputesEverything() {
        add(doc("generation:1", "gatto", "generation", 1));
        int before = embedding.embedded.get();

        new VectorIndexer(vectorStore, repository, "modello-b").upsertIfChanged(List.of(doc("generation:1", "gatto", "generation", 1)));

        assertThat(embedding.embedded.get()).isGreaterThan(before);
        assertThat(repository.find("generation:1").orElseThrow().model()).isEqualTo("modello-b");
    }

    @Test
    void aNewIndexerOnTheSameDataSeesWhatIsAlreadyIndexed() {
        add(doc("generation:1", "un felino", "generation", 1));
        int before = embedding.embedded.get();

        new VectorIndexer(vectorStore, repository, "modello-a").upsertIfChanged(List.of(doc("generation:1", "un felino", "generation", 1)));

        assertThat(embedding.embedded.get()).isEqualTo(before);
        assertThat(search(SearchRequest.builder().query("gatto").build())).extracting(Document::getId).containsExactly("generation:1");
    }

    @Test
    void passagesAndQueriesGetTheE5Prefixes() {
        add(doc("generation:1", "gatto", "generation", 1));
        search(SearchRequest.builder().query("felino").build());

        assertThat(embedding.seen).contains("passage: gatto", "query: felino");
        // il testo salvato (e restituito) e' quello nudo, senza prefisso
        assertThat(repository.find("generation:1").orElseThrow().content()).isEqualTo("gatto");
    }

    @Test
    void deleteByIdsAndByFilterRemovesRowsAndSearchResults() {
        add(doc("generation:1", "gatto", "generation", 1), doc("chatmessage:1", "gatto", "chat", 1), doc("chatmessage:2", "auto", "chat", 2));

        indexer.delete(List.of("generation:1"));
        assertThat(repository.idsOfType("generation")).isEmpty();
        vectorStore.delete(typeIs("chat"));

        assertThat(repository.count()).isZero();
        assertThat(jdbc.sql("select count(*) from vector_store").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsDocumentsWithoutTheRequiredMetadata() {
        assertThatThrownBy(() -> add(Document.builder().id("x").text("t").metadata(Map.of("type", "chat")).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adminApiFindsListsCountsAndReembeds() {
        add(doc("generation:1", "gatto", "generation", 1), doc("chatmessage:1", "auto", "chat", 1), doc("chatmessage:2", "mare", "chat", 2));

        assertThat(repository.find("generation:1")).hasValueSatisfying(d -> {
            assertThat(d.type()).isEqualTo("generation");
            assertThat(d.refId()).isEqualTo(1L);
            assertThat(d.model()).isEqualTo("modello-a");
            assertThat(d.hash()).isEqualTo(VectorIndexer.hash("gatto"));
            assertThat(d.dimensions()).isEqualTo(VectorIndexer.DIMENSIONS);
            assertThat(d.updatedAt()).isAfter(Instant.EPOCH);
        });
        assertThat(repository.find("nope")).isEmpty();
        assertThat(repository.countsByType()).containsEntry("chat", 2L).containsEntry("generation", 1L);
        VectorDocumentRepository.Listing chats = repository.list(typeIs("chat"), 1, 1);
        assertThat(chats.total()).isEqualTo(2);
        assertThat(chats.totalPages()).isEqualTo(2);
        assertThat(chats.documents()).hasSize(1);
        assertThat(chats.hasNext()).isTrue();
        assertThat(repository.list(null, 1, 10).documents()).hasSize(3);
        assertThat(repository.list(typeIs("chat"), 99, 1).page()).isEqualTo(2); // pagina oltre la fine: ricade sull'ultima

        int before = embedding.embedded.get();
        assertThat(indexer.reembed("generation:1")).isTrue();
        assertThat(embedding.embedded.get()).isGreaterThan(before); // ricalcolato anche se nulla e' cambiato
        assertThat(indexer.reembed("nope")).isFalse();
    }

    @Test
    void aMetadataOnlyChangeIsSavedWithoutReEmbedding() {
        add(Document.builder().id("note:1").text("gatto").metadata(Map.of("type", "note", "refId", 1L)).build());
        int before = embedding.embedded.get();

        add(Document.builder().id("note:1").text("gatto").metadata(Map.of("type", "note", "refId", 1L, "title", "Idea")).build());

        assertThat(embedding.embedded.get()).isEqualTo(before);
        assertThat(repository.find("note:1").orElseThrow().metadata()).containsEntry("title", "Idea");
        assertThat(search(SearchRequest.builder().query("gatto").build())).extracting(Document::getId).contains("note:1"); // il vettore c'e' ancora
    }

    @Test
    void reloadedNumbersCompareEqualSoNothingIsRewrittenNeedlessly() throws Exception {
        Document d = doc("generation:1", "gatto", "generation", 1);
        add(d);
        Instant stamp = repository.find("generation:1").orElseThrow().updatedAt();
        Thread.sleep(5);

        add(d); // dal DB refId torna Integer, nel documento e' Long: stesso valore

        assertThat(repository.find("generation:1").orElseThrow().updatedAt()).isEqualTo(stamp);
    }

    private static Filter.Expression createdAt(Filter.ExpressionType type, long millis) {
        return new Filter.Expression(type, new Filter.Key("createdAt"), new Filter.Value(millis));
    }

    private void withCreatedAt(String id, long refId, String text, Long createdAt) {
        Map<String, Object> metadata = new HashMap<>(Map.of("type", "chat", "refId", refId));
        if (createdAt != null) {
            metadata.put("createdAt", createdAt);
        }
        add(Document.builder().id(id).text(text).metadata(metadata).build());
    }

    @Test
    void numericComparisonFiltersMatchOnlyDocumentsThatCarryTheKey() {
        withCreatedAt("chatmessage:1", 1, "uno", 1000L);
        withCreatedAt("chatmessage:2", 2, "due", 2000L);
        withCreatedAt("chatmessage:3", 3, "tre", 3000L);
        withCreatedAt("chatmessage:4", 4, "senza data", null);

        assertThat(ids(repository.list(createdAt(Filter.ExpressionType.GTE, 2000), 1, 10))).containsExactly("chatmessage:3", "chatmessage:2");
        assertThat(ids(repository.list(createdAt(Filter.ExpressionType.GT, 2000), 1, 10))).containsExactly("chatmessage:3");
        assertThat(ids(repository.list(createdAt(Filter.ExpressionType.LTE, 2000), 1, 10))).containsExactly("chatmessage:2", "chatmessage:1");
        assertThat(ids(repository.list(createdAt(Filter.ExpressionType.LT, 1000), 1, 10))).isEmpty();
        // la stessa semantica vale per la ricerca per significato
        assertThat(search(SearchRequest.builder().query("uno").topK(10).filterExpression(createdAt(Filter.ExpressionType.GTE, 2000)).build()))
                .extracting(Document::getId).doesNotContain("chatmessage:1", "chatmessage:4");
        // il documento senza createdAt non entra in nessun confronto, ma c'e' nella lista senza filtro
        assertThat(repository.list(null, 1, 10).documents()).hasSize(4);
    }

    @Test
    void listIsOrderedByCreationDateNewestFirstNotByIndexingTime() {
        withCreatedAt("chatmessage:1", 1, "il piu' vecchio, indicizzato per ultimo", 1000L);
        withCreatedAt("chatmessage:2", 2, "il piu' recente", 9000L);
        withCreatedAt("chatmessage:3", 3, "in mezzo", 5000L);

        assertThat(ids(repository.list(null, 1, 10))).containsExactly("chatmessage:2", "chatmessage:3", "chatmessage:1");
        assertThat(repository.find("chatmessage:2").orElseThrow().createdAt()).isEqualTo(Instant.ofEpochMilli(9000L));
    }

    private static List<String> ids(VectorDocumentRepository.Listing listing) {
        return listing.documents().stream().map(org.hexa.core.search.domain.IndexedDocument::id).toList();
    }

    @Test
    void kindAndFavouriteFiltersWorkOnSearchAndListAndAnUnchangedDocumentIsNotRewritten() {
        Document image = Document.builder().id("generation:1").text("un faro").metadata(Map.of("type", "generation", "refId", 1L,
                "kind", "IMAGE", "favourite", true, "outputs", 2, "files", List.of("a.png", "b.png"))).build();
        Document video = Document.builder().id("generation:2").text("un faro").metadata(Map.of("type", "generation", "refId", 2L,
                "kind", "VIDEO", "favourite", false, "outputs", 1, "files", List.of("c.mp4"))).build();
        add(image, video, doc("note:1", "un faro", "note", 3));

        var favouriteImages = org.hexa.core.search.domain.DocumentFilter.NONE;
        favouriteImages = new org.hexa.core.search.domain.DocumentFilter(null, null, null, "IMAGE", true);
        var onlyVideos = new org.hexa.core.search.domain.DocumentFilter(null, null, null, "VIDEO", false);
        var onlyFavourites = new org.hexa.core.search.domain.DocumentFilter(null, null, null, null, true);

        assertThat(search(SearchRequest.builder().query("faro").topK(10).filterExpression(PgVectorIndex.expression(favouriteImages)).build()))
                .extracting(Document::getId).containsExactly("generation:1");
        assertThat(repository.list(PgVectorIndex.expression(onlyVideos), 1, 10).documents()).extracting(d -> d.id()).containsExactly("generation:2");
        assertThat(repository.list(PgVectorIndex.expression(onlyFavourites), 1, 10).documents()).extracting(d -> d.id()).containsExactly("generation:1");
        // liste/booleani/interi sopravvivono al round-trip JSON
        assertThat(repository.find("generation:1").orElseThrow().metadata()).containsEntry("files", List.of("a.png", "b.png"))
                .containsEntry("favourite", true);

        // una seconda riconciliazione con gli stessi dati non riscrive nulla (nessun churn per i tipi nuovi)
        long before = indexedAt("generation:1");
        add(image, video);
        assertThat(indexedAt("generation:1")).isEqualTo(before);
    }

    @Test
    void tagFilterMatchesAnElementOfTheTagsArrayOnSearchAndList() {
        Document two = Document.builder().id("generation:1").text("un faro").metadata(Map.of("type", "generation", "refId", 1L,
                "tags", List.of("mare", "vacanze 2026"))).build();
        Document one = Document.builder().id("imported:2").text("un faro").metadata(Map.of("type", "imported", "refId", 2L,
                "tags", List.of("montagna"))).build();
        add(two, one, doc("note:1", "un faro", "note", 3)); // senza chiave tags: non combacia

        var sea = new org.hexa.core.search.domain.DocumentFilter(null, null, null, null, false, "mare");
        var holidays = new org.hexa.core.search.domain.DocumentFilter(null, null, null, null, false, "vacanze 2026");
        var seaNotes = new org.hexa.core.search.domain.DocumentFilter("note", null, null, null, false, "mare");
        var missing = new org.hexa.core.search.domain.DocumentFilter(null, null, null, null, false, "mar"); // niente match parziale

        assertThat(search(SearchRequest.builder().query("faro").topK(10).filterExpression(PgVectorIndex.expression(sea)).build()))
                .extracting(Document::getId).containsExactly("generation:1");
        assertThat(repository.list(PgVectorIndex.expression(holidays), 1, 10).documents()).extracting(d -> d.id()).containsExactly("generation:1");
        assertThat(repository.list(PgVectorIndex.expression(seaNotes), 1, 10).documents()).isEmpty();
        assertThat(repository.list(PgVectorIndex.expression(missing), 1, 10).documents()).isEmpty();
        assertThat(repository.find("generation:1").orElseThrow().metadata()).containsEntry("tags", List.of("mare", "vacanze 2026"));
    }

    private long indexedAt(String id) {
        return ((Number) repository.find(id).orElseThrow().metadata().get("indexedAt")).longValue();
    }
}
