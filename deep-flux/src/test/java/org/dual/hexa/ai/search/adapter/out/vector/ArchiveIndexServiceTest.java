package org.dual.hexa.ai.search.adapter.out.vector;

import java.util.List;
import java.util.stream.Stream;

import org.dual.hexa.ai.search.adapter.out.vector.FakeEmbeddingModel;
import org.dual.hexa.ai.search.adapter.out.vector.VectorDocumentRepository;
import org.dual.hexa.ai.search.adapter.out.vector.VectorIndexer;
import org.dual.hexa.ai.chat.domain.ChatConversation;
import org.dual.hexa.ai.chat.domain.ChatMessage;
import org.dual.hexa.ai.chat.domain.ChatMessageRole;
import org.dual.hexa.app.generation.adapter.out.search.GenerationSearchSource;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.ai.chat.port.out.IChatConversationStore;
import org.dual.hexa.ai.chat.port.out.IChatMessageStore;
import org.dual.hexa.app.generation.port.out.IGenerationStore;
import org.dual.hexa.ai.search.application.ArchiveIndexService;
import org.dual.hexa.ai.search.domain.DocumentTypes;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.ai.chat.adapter.out.search.ChatSearchSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
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
    private IGenerationStore generations;
    @Autowired
    private IChatMessageStore messages;
    @Autowired
    private IChatConversationStore conversations;

    private FakeEmbeddingModel embedding;
    private VectorStore vectorStore;
    private VectorDocumentRepository documents;
    private VectorIndexer indexer;
    private ISystemEvents systemEvents;
    private ArchiveIndexService service;

    @BeforeEach
    void setUp() {
        clean();
        embedding = new FakeEmbeddingModel();
        vectorStore = SemanticSearchConfig.pgVectorStore(jdbcTemplate, embedding);
        documents = new VectorDocumentRepository(jdbc, objectMapper);
        indexer = new VectorIndexer(vectorStore, documents, "modello-a");
        systemEvents = mock(ISystemEvents.class);
        service = serviceOver(indexer);
    }

    @AfterEach
    void clean() {
        jdbc.sql("delete from chat_message").update();
        jdbc.sql("delete from chat_conversation").update();
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
    void importedImagesAreIndexedAsTheirOwnTypeAndNotAsGenerations() {
        Generation ok = generation("un felino sul divano", GenerationStatus.SUCCEEDED);
        Generation imported = Generation.imported("importata.png", java.time.Instant.parse("2026-10-04T10:00:00Z"));
        imported.applyAnalysis("una barca a vela al tramonto", List.of("barca", "mare"));
        imported = generations.save(imported);

        service.reconcile();

        assertThat(documents.idsOfType("generation")).containsExactly("generation:" + ok.getId());
        assertThat(documents.idsOfType("imported")).containsExactly("imported:" + imported.getId());
        assertThat(documents.find("imported:" + imported.getId()).orElseThrow().metadata()).containsEntry("kind", "IMAGE");
    }

    @Test
    void generationDocumentsCarryTagsAndArtifactMetadataAndASecondReconcileChangesNothing() {
        Generation g = new Generation("pred-art", "owner/model-x", null, "una volpe", "{\"aspect_ratio\":\"9:16\"}");
        g.setStatus(GenerationStatus.SUCCEEDED);
        g.setImageFilenames(new java.util.ArrayList<>(List.of("a.png", "b.png", "c.png")));
        g.setFavouriteFilenames(new java.util.LinkedHashSet<>(List.of("c.png")));
        g = generations.save(g);

        service.reconcile();

        var stored = documents.find("generation:" + g.getId()).orElseThrow();
        assertThat(stored.content()).startsWith("una volpe" + DocumentTypes.TAGS_SEPARATOR).contains("verticale").contains("owner/model-x");
        assertThat(stored.visibleContent()).isEqualTo("una volpe");
        assertThat(stored.metadata()).containsEntry("kind", "IMAGE").containsEntry("model", "owner/model-x").containsEntry("favourite", true)
                .containsEntry("outputs", 3).containsEntry("files", List.of("c.png", "a.png", "b.png")) // la star per prima
                .containsEntry("favouriteFiles", List.of("c.png"));

        int embedded = embedding.embedded.get();
        long indexedAt = ((Number) stored.metadata().get("indexedAt")).longValue();
        service.reconcile();
        assertThat(embedding.embedded.get()).isEqualTo(embedded);
        assertThat(((Number) documents.find("generation:" + g.getId()).orElseThrow().metadata().get("indexedAt")).longValue()).isEqualTo(indexedAt);
    }

    @Test
    void userTagsOfGenerationsFilesAndConversationsAreIndexedAsMetadataOnlyNeverInTheEmbeddedText() {
        Generation g = generation("una volpe", GenerationStatus.SUCCEEDED);
        g.setImageFilenames(new java.util.ArrayList<>(List.of("a.png", "b.png")));
        g.getTags().add("animali");
        g.getFileTags().add(new org.dual.hexa.app.generation.domain.FileTag("b.png", "bosco"));
        g = generations.save(g);
        // senza titolo ma taggata: il testo sono i soli tag, altrimenti la riconciliazione la scarterebbe
        ChatConversation conversation = new ChatConversation();
        conversation.getTags().add("progetti");
        conversation = conversations.save(conversation);

        service.reconcile();

        var generationDoc = documents.find("generation:" + g.getId()).orElseThrow();
        assertThat(generationDoc.metadata()).containsEntry("tags", List.of("animali", "bosco"));
        assertThat(generationDoc.content()).doesNotContain("animali").doesNotContain("bosco");
        var conversationDoc = documents.find("conversation:" + conversation.getId()).orElseThrow();
        assertThat(conversationDoc.metadata()).containsEntry("tags", List.of("progetti"));
        assertThat(conversationDoc.content()).isEqualTo("progetti");
        assertThat(documents.tagCounts()).containsEntry("animali", 1L).containsEntry("progetti", 1L);

        conversation.setTitle("Il progetto");
        conversations.save(conversation);
        service.reconcile();
        var titled = documents.find("conversation:" + conversation.getId()).orElseThrow();
        assertThat(titled.content()).isEqualTo("Il progetto");
        assertThat(titled.metadata()).containsEntry("tags", List.of("progetti"));
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

        // conversazione e messaggio (nuovi) + la generazione (il testo ora porta anche le tag d'indice: nuovo hash); la nota vecchia solo metadata
        assertThat(embedding.embedded.get()).isEqualTo(before + 3);
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

        generations.deleteAllById(List.of(g.getId()));
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
        assertThat(found).allSatisfy(d -> assertThat(d.getText().length()).isLessThanOrEqualTo(DocumentTypes.MAX_CHARS));
        assertThat(documents.idsOfType("conversation")).doesNotContain("conversation:" + conversation.getId());
        assertThat(Stream.of(documents.idsOfType("generation")).count()).isEqualTo(1);
    }

    /** L'indice reale (store pgvector) con le sorgenti vere di generation e chat, sopra lo stesso DB di test. */
    @SuppressWarnings("unchecked")
    private ArchiveIndexService serviceOver(VectorIndexer over) {
        var generationsSource = new GenerationSearchSource(generationsPort(), mock(org.dual.hexa.app.generation.port.in.ILoraPresets.class),
                org.mockito.Mockito.mock(ObjectProvider.class), objectMapper);
        return new ArchiveIndexService(new PgVectorIndex(vectorStore, over, documents),
                List.of(generationsSource, new ChatSearchSource(messages, conversations, org.mockito.Mockito.mock(ObjectProvider.class))), systemEvents, transactionManager);
    }

    /** La porta delle generazioni vista dall'indice: solo le riuscite, lette dallo store di test. */
    private org.dual.hexa.app.generation.port.in.IGenerations generationsPort() {
        var port = org.mockito.Mockito.mock(org.dual.hexa.app.generation.port.in.IGenerations.class);
        org.mockito.Mockito.when(port.succeeded()).thenAnswer(invocation -> generations.findByStatusIn(
                List.of(org.dual.hexa.app.generation.domain.GenerationStatus.SUCCEEDED)));
        return port;
    }
}
