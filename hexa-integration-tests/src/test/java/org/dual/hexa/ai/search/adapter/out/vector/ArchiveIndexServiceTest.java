package org.dual.hexa.ai.search.adapter.out.vector;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.hexa.ai.chat.adapter.out.search.ChatSearchSource;
import org.dual.hexa.ai.chat.domain.ChatConversation;
import org.dual.hexa.ai.chat.domain.ChatMessage;
import org.dual.hexa.ai.chat.domain.ChatMessageRole;
import org.dual.hexa.ai.chat.port.out.IChatConversationStore;
import org.dual.hexa.ai.chat.port.out.IChatMessageStore;
import org.dual.hexa.ai.search.application.ArchiveIndexService;
import org.dual.hexa.ai.search.domain.DocumentTypes;
import org.dual.hexa.ai.search.domain.SearchableDocument;
import org.dual.hexa.ai.search.port.in.ISearchableSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Riconciliazione dell'indice contro il DB di test (commit reali: si ripulisce a mano), con l'indice pgvector vero, la sorgente della chat vera e una sorgente
 * finta dell'host ({@code StubSource}, tipo {@code item}). Embedding finto.
 */
@SpringBootTest
class ArchiveIndexServiceTest {

    /** Una sorgente di un host qualunque: i documenti sono quelli che il test mette nella lista. */
    private static final class StubSource implements ISearchableSource {
        final List<SearchableDocument> docs = new ArrayList<>();

        @Override
        public Set<String> types() {
            return Set.of("item");
        }

        @Override
        public List<SearchableDocument> documents() {
            return List.copyOf(docs);
        }

        void add(long id, String text) {
            docs.add(SearchableDocument.of("item:" + id, "item", id, null, Instant.parse("2026-10-04T10:00:00Z"), text, Map.of()));
        }
    }

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private IChatMessageStore messages;
    @Autowired
    private IChatConversationStore conversations;

    private FakeEmbeddingModel embedding;
    private VectorStore vectorStore;
    private VectorDocumentRepository documents;
    private VectorIndexer indexer;
    private ISystemEvents systemEvents;
    private StubSource items;
    private ArchiveIndexService service;

    @BeforeEach
    void setUp() {
        clean();
        embedding = new FakeEmbeddingModel();
        vectorStore = SemanticSearchConfig.pgVectorStore(jdbcTemplate, embedding);
        documents = new VectorDocumentRepository(jdbc, objectMapper);
        indexer = new VectorIndexer(vectorStore, documents, "modello-a");
        systemEvents = mock(ISystemEvents.class);
        items = new StubSource();
        service = serviceOver(indexer);
    }

    @AfterEach
    void clean() {
        jdbc.sql("delete from chat_message").update();
        jdbc.sql("delete from chat_conversation").update();
        jdbc.sql("delete from vector_store").update();
    }

    @Test
    void indexesTheHostSourceChatMessagesAndTitlesButNotTheErrorTurns() {
        items.add(1, "un felino sul divano");
        ChatConversation conversation = new ChatConversation();
        conversation.setTitle("Il castello del drago");
        conversation = conversations.save(conversation);
        messages.save(new ChatMessage(conversation, ChatMessageRole.USER, "vorrei un ritratto", null));
        messages.save(ChatMessage.errorTurn(conversation, "Errore nel contattare l'assistente"));

        service.reconcile();

        assertThat(documents.idsOfType("item")).containsExactly("item:1");
        assertThat(documents.idsOfType("conversation")).containsExactly("conversation:" + conversation.getId());
        assertThat(documents.idsOfType("chat")).hasSize(1); // il turno d'errore non si indicizza
        assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("gatto").topK(1).build()))
                .extracting(Document::getId).containsExactly("item:1");
        verify(systemEvents, never()).record(anyString(), any(Throwable.class));
    }

    @Test
    void userTagsOfConversationsAreIndexedAsMetadataAndAUntitledTaggedConversationIsKept() {
        // senza titolo ma taggata: il testo sono i soli tag, altrimenti la riconciliazione la scarterebbe
        ChatConversation conversation = new ChatConversation();
        conversation.getTags().add("progetti");
        conversation = conversations.save(conversation);

        service.reconcile();

        var conversationDoc = documents.find("conversation:" + conversation.getId()).orElseThrow();
        assertThat(conversationDoc.metadata()).containsEntry("tags", List.of("progetti"));
        assertThat(conversationDoc.content()).isEqualTo("progetti");
        assertThat(documents.tagCounts()).containsEntry("progetti", 1L);

        conversation.setTitle("Il progetto");
        conversations.save(conversation);
        service.reconcile();
        var titled = documents.find("conversation:" + conversation.getId()).orElseThrow();
        assertThat(titled.content()).isEqualTo("Il progetto");
        assertThat(titled.metadata()).containsEntry("tags", List.of("progetti"));
    }

    @Test
    void documentsCarryTheCreationDateOfTheirSourceAndLegacyOnesGetItWithoutReEmbedding() {
        ChatConversation conversation = new ChatConversation();
        conversation.setTitle("Il castello del drago");
        conversation = conversations.save(conversation);
        ChatMessage message = messages.save(new ChatMessage(conversation, ChatMessageRole.USER, "vorrei un ritratto", null));
        // una nota vecchia gia' indicizzata SENZA createdAt (indice precedente al filtro per periodo)
        indexer.upsertIfChanged(List.of(Document.builder().id("note:old").text("appunto vecchio")
                .metadata(Map.of("type", "note", "refId", 1_700_000_000_000L)).build()));
        int before = embedding.embedded.get();

        service.reconcile();

        // conversazione e messaggio (nuovi); la nota vecchia riceve solo i metadata, senza nuovo embedding
        assertThat(embedding.embedded.get()).isEqualTo(before + 2);
        assertThat(documents.find("chatmessage:" + message.getId()).orElseThrow().createdAt()).isEqualTo(message.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
        assertThat(documents.find("conversation:" + conversation.getId()).orElseThrow().metadata()).containsKey("createdAt");
        assertThat(documents.find("note:old").orElseThrow().metadata().get("createdAt")).isEqualTo(1_700_000_000_000L);
        assertThat(documents.find("note:old").orElseThrow().content()).isEqualTo("appunto vecchio");
    }

    @Test
    void isIdempotentAndOnlyEmbedsWhatChanged() {
        items.add(1, "gatto");
        service.reconcile();
        int afterFirst = embedding.embedded.get();
        long indexedAt = ((Number) documents.find("item:1").orElseThrow().metadata().get("indexedAt")).longValue();

        service.reconcile();
        assertThat(embedding.embedded.get()).isEqualTo(afterFirst);
        assertThat(((Number) documents.find("item:1").orElseThrow().metadata().get("indexedAt")).longValue()).isEqualTo(indexedAt);

        items.add(2, "montagna");
        service.reconcile();
        assertThat(embedding.embedded.get()).isEqualTo(afterFirst + 1);
        assertThat(documents.count()).isEqualTo(2);
    }

    @Test
    void removesDocumentsWhoseSourceRowIsGone() {
        items.add(1, "gatto");
        service.reconcile();
        assertThat(documents.count()).isEqualTo(1);

        items.docs.clear();
        service.reconcile();

        assertThat(documents.count()).isZero();
    }

    @Test
    void oneFailingDocumentDoesNotStopTheOthers() {
        EmbeddingModel failingOnPoison = new FakeEmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                if (request.getInstructions().stream().anyMatch(t -> t.contains("veleno"))) {
                    throw new IllegalStateException("embedding fallito");
                }
                return super.call(request);
            }
        };
        VectorIndexer fragile = new VectorIndexer(SemanticSearchConfig.pgVectorStore(jdbcTemplate, failingOnPoison), documents, "modello-a");
        ArchiveIndexService fragileService = serviceOver(fragile);
        items.add(1, "veleno");
        items.add(2, "gatto");

        fragileService.reconcile();

        assertThat(documents.idsOfType("item")).containsExactly("item:2");
        verify(systemEvents).record(anyString(), any(Throwable.class));
    }

    @Test
    void longTextsAreTruncatedAndBlankOnesSkipped() {
        items.add(1, "gatto " + "x".repeat(5000));
        items.add(2, "   ");
        ChatConversation conversation = conversations.save(new ChatConversation()); // senza titolo
        service.reconcile();

        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder().query("gatto").topK(5).build());
        assertThat(found).allSatisfy(d -> assertThat(d.getText().length()).isLessThanOrEqualTo(DocumentTypes.MAX_CHARS));
        assertThat(documents.idsOfType("conversation")).doesNotContain("conversation:" + conversation.getId());
        assertThat(documents.idsOfType("item")).containsExactly("item:1");
    }

    /** L'indice reale (store pgvector) con la sorgente finta dell'host e quella vera della chat, sopra lo stesso DB di test. */
    @SuppressWarnings("unchecked")
    private ArchiveIndexService serviceOver(VectorIndexer over) {
        return new ArchiveIndexService(new PgVectorIndex(vectorStore, over, documents),
                List.of(items, new ChatSearchSource(messages, conversations, mock(ObjectProvider.class))), systemEvents, transactionManager);
    }
}
