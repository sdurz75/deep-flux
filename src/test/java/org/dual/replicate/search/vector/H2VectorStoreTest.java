package org.dual.replicate.search.vector;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contro l'H2 in-memory di test (schema Flyway V19); embedding finto: nessun modello vero. */
@SpringBootTest
class H2VectorStoreTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private FakeEmbeddingModel embedding;
    private H2VectorStore store;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from VECTOR_DOC").update();
        embedding = new FakeEmbeddingModel();
        store = new H2VectorStore(jdbc, embedding, objectMapper, "modello-a");
    }

    private static Document doc(String id, String text, String type, long refId) {
        return Document.builder().id(id).text(text).metadata(Map.of("type", type, "refId", refId)).build();
    }

    private static Filter.Expression typeIs(String type) {
        return new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("type"), new Filter.Value(type));
    }

    @Test
    void ranksBySemanticSimilarityAndReturnsScoresInOrder() {
        store.add(List.of(doc("generation:1", "un felino sul divano", "generation", 1),
                doc("generation:2", "auto sportiva in montagna", "generation", 2),
                doc("generation:3", "castello con drago", "generation", 3)));

        List<Document> found = store.similaritySearch(SearchRequest.builder().query("gatto").topK(3).build());

        assertThat(found).extracting(Document::getId).first().isEqualTo("generation:1");
        assertThat(found).hasSize(3);
        assertThat(found.get(0).getScore()).isGreaterThan(found.get(1).getScore());
        assertThat(found.get(0).getMetadata()).containsEntry("type", "generation");
    }

    @Test
    void topKAndSimilarityThresholdAreRespected() {
        store.add(List.of(doc("a", "gatto", "generation", 1), doc("b", "auto", "generation", 2), doc("c", "mare", "generation", 3)));

        assertThat(store.similaritySearch(SearchRequest.builder().query("gatto").topK(2).build())).hasSize(2);
        assertThat(store.similaritySearch(SearchRequest.builder().query("gatto").topK(5).similarityThreshold(0.9).build()))
                .extracting(Document::getId).containsExactly("a");
    }

    @Test
    void filtersRestrictTheCandidates() {
        store.add(List.of(doc("generation:1", "gatto", "generation", 1), doc("chatmessage:1", "gatto", "chat", 1),
                Document.builder().id("chatmessage:2").text("gatto grigio").metadata(Map.of("type", "chat", "refId", 2L, "conversationId", 9L)).build()));

        assertThat(store.similaritySearch(SearchRequest.builder().query("gatto").topK(5).filterExpression(typeIs("chat")).build()))
                .extracting(Document::getId).containsExactlyInAnyOrder("chatmessage:1", "chatmessage:2");
        Filter.Expression inConversation = new Filter.Expression(Filter.ExpressionType.AND, typeIs("chat"),
                new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("conversationId"), new Filter.Value(9)));
        assertThat(store.similaritySearch(SearchRequest.builder().query("gatto").topK(5).filterExpression(inConversation).build()))
                .extracting(Document::getId).containsExactly("chatmessage:2");
        assertThatThrownBy(() -> store.similaritySearch(SearchRequest.builder().query("gatto")
                .filterExpression(new Filter.Expression(Filter.ExpressionType.GT, new Filter.Key("refId"), new Filter.Value(1))).build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unchangedDocumentsAreNotReEmbedded() {
        Document d = doc("generation:1", "gatto", "generation", 1);
        store.add(List.of(d));
        int afterFirst = embedding.embedded.get();

        store.add(List.of(d));
        assertThat(embedding.embedded.get()).isEqualTo(afterFirst);

        store.add(List.of(doc("generation:1", "gatto nero", "generation", 1))); // testo cambiato
        assertThat(embedding.embedded.get()).isGreaterThan(afterFirst);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aDifferentEmbeddingModelRecomputesEverything() {
        store.add(List.of(doc("generation:1", "gatto", "generation", 1)));
        int before = embedding.embedded.get();

        new H2VectorStore(jdbc, embedding, objectMapper, "modello-b").add(List.of(doc("generation:1", "gatto", "generation", 1)));

        assertThat(embedding.embedded.get()).isGreaterThan(before);
    }

    @Test
    void documentsSurviveARestartOfTheStore() {
        store.add(List.of(doc("generation:1", "un felino", "generation", 1)));

        H2VectorStore reloaded = new H2VectorStore(jdbc, embedding, objectMapper, "modello-a");

        assertThat(reloaded.similaritySearch(SearchRequest.builder().query("gatto").build()))
                .extracting(Document::getId).containsExactly("generation:1");
    }

    @Test
    void passagesAndQueriesGetTheE5Prefixes() {
        store.add(List.of(doc("generation:1", "gatto", "generation", 1)));
        store.similaritySearch(SearchRequest.builder().query("felino").build());

        assertThat(embedding.seen).contains("passage: gatto", "query: felino");
    }

    @Test
    void deleteByIdsAndByFilterRemovesRowsAndSearchResults() {
        store.add(List.of(doc("generation:1", "gatto", "generation", 1), doc("chatmessage:1", "gatto", "chat", 1),
                doc("chatmessage:2", "auto", "chat", 2)));

        store.delete(List.of("generation:1"));
        assertThat(store.idsOfType("generation")).isEmpty();
        store.delete(typeIs("chat"));

        assertThat(store.size()).isZero();
        assertThat(jdbc.sql("select count(*) from VECTOR_DOC").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsDocumentsWithoutTheRequiredMetadata() {
        assertThatThrownBy(() -> store.add(List.of(Document.builder().id("x").text("t").metadata(Map.of("type", "chat")).build())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
