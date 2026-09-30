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
import org.dual.replicate.service.AppErrorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private ObjectMapper objectMapper;
    @Autowired
    private GenerationRepository generations;
    @Autowired
    private ChatMessageRepository messages;
    @Autowired
    private ChatConversationRepository conversations;

    private FakeEmbeddingModel embedding;
    private H2VectorStore store;
    private AppErrorService appErrors;
    private ArchiveIndexService service;

    @BeforeEach
    void setUp() {
        clean();
        embedding = new FakeEmbeddingModel();
        store = new H2VectorStore(jdbc, embedding, objectMapper, "modello-a");
        appErrors = mock(AppErrorService.class);
        service = new ArchiveIndexService(store, generations, messages, conversations, appErrors);
    }

    @AfterEach
    void clean() {
        messages.deleteAll();
        conversations.deleteAll();
        generations.deleteAll();
        jdbc.sql("delete from VECTOR_DOC").update();
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

        assertThat(store.idsOfType("generation")).containsExactly("generation:" + ok.getId());
        assertThat(store.idsOfType("conversation")).containsExactly("conversation:" + conversation.getId());
        assertThat(store.idsOfType("chat")).hasSize(1); // il turno d'errore non si indicizza
        assertThat(store.similaritySearch(SearchRequest.builder().query("gatto").topK(1).build()))
                .extracting(Document::getId).containsExactly("generation:" + ok.getId());
        verify(appErrors, never()).record(anyString(), any(Throwable.class));
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
        assertThat(store.size()).isEqualTo(2);
        assertThat(g.getId()).isNotNull();
    }

    @Test
    void removesDocumentsWhoseSourceRowIsGone() {
        Generation g = generation("gatto", GenerationStatus.SUCCEEDED);
        service.reconcile();
        assertThat(store.size()).isEqualTo(1);

        generations.deleteById(g.getId());
        service.reconcile();

        assertThat(store.size()).isZero();
    }

    @Test
    void oneFailingDocumentDoesNotStopTheOthers() {
        EmbeddingModel failingOnPoison = new FakeEmbeddingModel() {
            @Override
            public List<float[]> embed(List<String> texts) {
                if (texts.stream().anyMatch(t -> t.contains("veleno"))) {
                    throw new IllegalStateException("embedding fallito");
                }
                return super.embed(texts);
            }
        };
        H2VectorStore fragile = new H2VectorStore(jdbc, failingOnPoison, objectMapper, "modello-a");
        ArchiveIndexService fragileService = new ArchiveIndexService(fragile, generations, messages, conversations, appErrors);
        generation("veleno", GenerationStatus.SUCCEEDED);
        Generation fine = generation("gatto", GenerationStatus.SUCCEEDED);

        fragileService.reconcile();

        assertThat(fragile.idsOfType("generation")).containsExactly("generation:" + fine.getId());
        verify(appErrors).record(anyString(), any(Throwable.class));
    }

    @Test
    void longTextsAreTruncatedAndBlankOnesSkipped() {
        generation("gatto " + "x".repeat(5000), GenerationStatus.SUCCEEDED);
        ChatConversation conversation = conversations.save(new ChatConversation()); // senza titolo
        service.reconcile();

        List<Document> found = store.similaritySearch(SearchRequest.builder().query("gatto").topK(5).build());
        assertThat(found).allSatisfy(d -> assertThat(d.getText().length()).isLessThanOrEqualTo(ArchiveIndexService.MAX_CHARS));
        assertThat(store.idsOfType("conversation")).doesNotContain("conversation:" + conversation.getId());
        assertThat(Stream.of(store.idsOfType("generation")).count()).isEqualTo(1);
    }
}
