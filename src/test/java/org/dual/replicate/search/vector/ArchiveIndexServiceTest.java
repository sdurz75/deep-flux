package org.dual.replicate.search.vector;

import java.util.List;
import java.util.stream.Stream;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.dual.replicate.repository.GenerationRepository;
import org.dual.replicate.service.SystemEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.vectorstore.VectorStore;
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

/** Riconciliazione dell'indice contro il DB di test (commit reali: si ripulisce a mano). Embedding finto. */
@SpringBootTest
class ArchiveIndexServiceTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GenerationRepository generations;
    @Autowired
    private ChatMessageRepository messages;
    @Autowired
    private ChatConversationRepository conversations;

    private FakeEmbeddingModel embedding;
    private VectorStore vectorStore;
    private VectorDocumentRepository documents;
    private VectorIndexer indexer;
    private SystemEventService systemEvents;
    private ArchiveIndexService service;

    @BeforeEach
    void setUp() {
        clean();
        embedding = new FakeEmbeddingModel();
        vectorStore = SemanticSearchConfig.pgVectorStore(jdbcTemplate, embedding);
        documents = new VectorDocumentRepository(jdbc, objectMapper);
        indexer = new VectorIndexer(vectorStore, documents, "modello-a");
        systemEvents = mock(SystemEventService.class);
        service = new ArchiveIndexService(indexer, documents, generations, messages, conversations, systemEvents, transactionManager);
    }

    @AfterEach
    void clean() {
        messages.deleteAll();
        conversations.deleteAll();
        generations.deleteAll();
        jdbc.sql("delete from vector_store").update();
    }

    private Generation generation(String prompt, GenerationStatus status) {
        Generation generation = new Generation("pred-" + prompt.hashCode(), "owner/model", null, prompt, null);
        generation.setStatus(status);
        return generations.save(generation);
    }

    @Test
    void indexesSucceededGenerationsChatMessagesAndTitlesButNotTheRest() {
        Generation ok = generation("un felino sul divano", GenerationStatus.SUCCEEDED);
        generation("auto in montagna", GenerationStatus.FAILED);
        ChatConversation conversation = new ChatConversation();
        conversation.setTitle("Il castello del drago");
        conversation = conversations.save(conversation);
        messages.save(new ChatMessage(conversation, ChatMessageRole.USER, "vorrei un ritratto", null));
        messages.save(ChatMessage.errorTurn(conversation, "Errore nel contattare l'assistente"));

        service.reconcile();

        assertThat(documents.idsOfType("generation")).containsExactly("generation:" + ok.getId());
        assertThat(documents.idsOfType("conversation")).containsExactly("conversation:" + conversation.getId());
        assertThat(documents.idsOfType("chat")).hasSize(1); // il turno d'errore non si indicizza
        assertThat(vectorStore.similaritySearch(SearchRequest.builder().query("gatto").topK(1).build()))
                .extracting(Document::getId).containsExactly("generation:" + ok.getId());
        verify(systemEvents, never()).record(anyString(), any(Throwable.class));
    }

    @Test
    void documentsCarryTheCreationDateOfTheirSourceAndLegacyOnesGetItWithoutReEmbedding() {
        Generation g = generation("gatto", GenerationStatus.SUCCEEDED);
        ChatConversation conversation = new ChatConversation();
        conversation.setTitle("Il castello del drago");
        conversation = conversations.save(conversation);
        ChatMessage message = messages.save(new ChatMessage(conversation, ChatMessageRole.USER, "vorrei un ritratto", null));
        // un documento gia' indicizzato SENZA createdAt (indice precedente al filtro per periodo) e una nota vecchia
        indexer.upsertIfChanged(List.of(Document.builder().id("generation:" + g.getId()).text("gatto")
                .metadata(java.util.Map.of("type", "generation", "refId", g.getId(), "kind", String.valueOf(g.getKind()))).build()));
        indexer.upsertIfChanged(List.of(Document.builder().id("note:old").text("appunto vecchio")
                .metadata(java.util.Map.of("type", "note", "refId", 1_700_000_000_000L)).build()));
        int before = embedding.embedded.get();

        service.reconcile();

        assertThat(embedding.embedded.get()).isEqualTo(before + 2); // solo conversazione e messaggio: gli altri solo metadata
        assertThat(documents.find("generation:" + g.getId()).orElseThrow().metadata()).containsEntry("createdAt", g.getCreatedAt().toEpochMilli());
        assertThat(documents.find("chatmessage:" + message.getId()).orElseThrow().createdAt()).isEqualTo(message.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(documents.find("conversation:" + conversation.getId()).orElseThrow().metadata()).containsKey("createdAt");
        assertThat(documents.find("note:old").orElseThrow().metadata().get("createdAt")).isEqualTo(1_700_000_000_000L);
        assertThat(documents.find("note:old").orElseThrow().content()).isEqualTo("appunto vecchio");
    }

    @Test
    void isIdempotentAndOnlyEmbedsWhatChanged() {
        Generation g = generation("gatto", GenerationStatus.SUCCEEDED);
        service.reconcile();
        int afterFirst = embedding.embedded.get();

        service.reconcile();
        assertThat(embedding.embedded.get()).isEqualTo(afterFirst);

        generation("montagna", GenerationStatus.SUCCEEDED);
        service.reconcile();
        assertThat(embedding.embedded.get()).isEqualTo(afterFirst + 1);
        assertThat(documents.count()).isEqualTo(2);
        assertThat(g.getId()).isNotNull();
    }

    @Test
    void removesDocumentsWhoseSourceRowIsGone() {
        Generation g = generation("gatto", GenerationStatus.SUCCEEDED);
        service.reconcile();
        assertThat(documents.count()).isEqualTo(1);

        generations.deleteById(g.getId());
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
        ArchiveIndexService fragileService = new ArchiveIndexService(fragile, documents, generations, messages, conversations, systemEvents, transactionManager);
        generation("veleno", GenerationStatus.SUCCEEDED);
        Generation fine = generation("gatto", GenerationStatus.SUCCEEDED);

        fragileService.reconcile();

        assertThat(documents.idsOfType("generation")).containsExactly("generation:" + fine.getId());
        verify(systemEvents).record(anyString(), any(Throwable.class));
    }

    @Test
    void longTextsAreTruncatedAndBlankOnesSkipped() {
        generation("gatto " + "x".repeat(5000), GenerationStatus.SUCCEEDED);
        ChatConversation conversation = conversations.save(new ChatConversation()); // senza titolo
        service.reconcile();

        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder().query("gatto").topK(5).build());
        assertThat(found).allSatisfy(d -> assertThat(d.getText().length()).isLessThanOrEqualTo(ArchiveIndexService.MAX_CHARS));
        assertThat(documents.idsOfType("conversation")).doesNotContain("conversation:" + conversation.getId());
        assertThat(Stream.of(documents.idsOfType("generation")).count()).isEqualTo(1);
    }
}
