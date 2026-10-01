package org.dual.replicate.search.vector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.dual.replicate.repository.GenerationRepository;
import org.dual.replicate.service.SystemEventService;
import org.dual.replicate.service.GenerationCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

/**
 * Tiene l'indice semantico allineato ai dati, con una RICONCILIAZIONE idempotente invece di ganci su ogni {@code save}:
 * aggiunge i documenti mancanti o cambiati ({@link VectorIndexer} salta gli invariati per hash del testo e modello), rimuove quelli la
 * cui riga sorgente non esiste piu'. Gira in background all'avvio (backfill), ogni {@code app.search.reindex-interval} e
 * dopo ogni generazione completata. Un documento che non si riesce a indicizzare e' registrato ({@link SystemEventService}) e
 * non ferma gli altri.
 *
 * <p>Cosa si indicizza: il prompt delle generazioni riuscite ({@code type=generation}), i messaggi delle chat non di errore
 * ({@code type=chat}), i titoli delle conversazioni ({@code type=conversation}).
 */
@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveIndexService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveIndexService.class);

    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_CONVERSATION = "conversation";
    /** Note manuali (create dalla UI /search): la riconciliazione non le crea ne' le rimuove, le timbra soltanto. */
    public static final String TYPE_NOTE = "note";
    /** ~512 token del modello: oltre, il tokenizer tronca comunque. */
    public static final int MAX_CHARS = 1800;

    private final VectorIndexer indexer;
    private final VectorDocumentRepository documents;
    private final GenerationRepository generations;
    private final ChatMessageRepository messages;
    private final ChatConversationRepository conversations;
    private final SystemEventService systemEvents;
    /** Solo la LETTURA delle sorgenti JPA e' in transazione (read-only): le scritture sull'indice vanno fuori, vedi {@link #reconcile()}. */
    private final TransactionTemplate readOnly;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "archive-indexer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean rerun = new AtomicBoolean();

    public ArchiveIndexService(VectorIndexer indexer, VectorDocumentRepository documents, GenerationRepository generations,
                               ChatMessageRepository messages, ChatConversationRepository conversations,
                               SystemEventService systemEvents, PlatformTransactionManager transactionManager) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.indexer = indexer;
        this.documents = documents;
        this.generations = generations;
        this.messages = messages;
        this.conversations = conversations;
        this.systemEvents = systemEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reindexOnStartup() {
        reindexAsync();
    }

    @EventListener
    public void onGenerationCompleted(GenerationCompletedEvent event) {
        reindexAsync();
    }

    @Scheduled(fixedDelayString = "${app.search.reindex-interval:5m}", initialDelayString = "${app.search.reindex-interval:5m}")
    public void sweep() {
        reindexAsync();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Non blocca il chiamante; richieste durante un giro ne fanno ripartire uno solo alla fine. */
    public void reindexAsync() {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        executor.execute(() -> {
            try {
                do {
                    rerun.set(false);
                    reconcile();
                } while (rerun.get());
            } finally {
                running.set(false);
            }
        });
    }

    /** {@code true} mentre un giro di riconciliazione e' in corso. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Un giro completo (sincrono). La lettura di generazioni/messaggi/conversazioni sta in una transazione read-only (accesso lazy
     * alle associazioni); le scritture sull'indice (stesso DataSource, quindi stessa connessione se fossero nella transazione) NO:
     * in una transazione read-only Postgres rifiuta gli INSERT e, dopo il primo errore, scarterebbe ogni comando successivo,
     * vanificando il "un documento difettoso non ferma gli altri".
     */
    public void reconcile() {
        try {
            Map<String, Document> wanted = readOnly.execute(status -> wantedDocuments());
            Set<String> stale = new HashSet<>();
            for (String type : List.of(TYPE_GENERATION, TYPE_CHAT, TYPE_CONVERSATION)) {
                stale.addAll(documents.idsOfType(type));
            }
            stale.removeAll(wanted.keySet());
            if (!stale.isEmpty()) {
                indexer.delete(new ArrayList<>(stale));
            }
            addAll(new ArrayList<>(wanted.values()));
            stampNotes();
        } catch (RuntimeException e) {
            systemEvents.record("reindex", e);
        }
    }

    /**
     * Le note nascono con {@code createdAt}; quelle precedenti al filtro per periodo non lo hanno: per le note {@code refId} e' il
     * millisecondo di creazione. Si riscrivono i soli metadata (testo e modello invariati: lo store non ri-embedda).
     */
    private void stampNotes() {
        for (String id : documents.idsOfType(TYPE_NOTE)) {
            documents.find(id).filter(doc -> !doc.metadata().containsKey("createdAt")).ifPresent(doc -> {
                Map<String, Object> metadata = new HashMap<>(doc.metadata());
                metadata.put("createdAt", doc.refId());
                indexer.upsertIfChanged(List.of(Document.builder().id(doc.id()).text(doc.content()).metadata(metadata).build()));
            });
        }
    }

    private void addAll(List<Document> toIndex) {
        for (int from = 0; from < toIndex.size(); from += 32) {
            List<Document> batch = toIndex.subList(from, Math.min(toIndex.size(), from + 32));
            try {
                indexer.upsertIfChanged(batch);
            } catch (RuntimeException batchFailure) {
                // Un documento difettoso non deve bloccare gli altri: si ripete uno per uno.
                for (Document document : batch) {
                    try {
                        indexer.upsertIfChanged(List.of(document));
                    } catch (RuntimeException e) {
                        systemEvents.record("reindex", e);
                    }
                }
            }
        }
    }

    private Map<String, Document> wantedDocuments() {
        Map<String, Document> wanted = new HashMap<>();
        for (Generation generation : generations.findByStatusIn(List.of(GenerationStatus.SUCCEEDED))) {
            put(wanted, "generation:" + generation.getId(), generation.getPrompt(),
                    metadata(TYPE_GENERATION, generation.getId(), generation.getConversationId(), generation.getCreatedAt(),
                            "kind", String.valueOf(generation.getKind())));
        }
        for (ChatMessage message : messages.findAll()) {
            if (!message.isError()) {
                put(wanted, "chatmessage:" + message.getId(), message.getContent(),
                        metadata(TYPE_CHAT, message.getId(), message.getConversation().getId(), message.getCreatedAt(),
                                "role", message.getRole().name()));
            }
        }
        for (ChatConversation conversation : conversations.findAll()) {
            put(wanted, "conversation:" + conversation.getId(), conversation.getTitle(),
                    metadata(TYPE_CONVERSATION, conversation.getId(), conversation.getId(), conversation.getCreatedAt()));
        }
        return wanted;
    }

    private static void put(Map<String, Document> wanted, String id, String text, Map<String, Object> metadata) {
        if (text == null || text.isBlank()) {
            return;
        }
        String trimmed = text.strip();
        wanted.put(id, Document.builder().id(id)
                .text(trimmed.length() > MAX_CHARS ? trimmed.substring(0, MAX_CHARS) : trimmed)
                .metadata(metadata).build());
    }

    private static Map<String, Object> metadata(String type, Long refId, Long conversationId, Instant createdAt, String... extra) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", type);
        metadata.put("refId", refId);
        if (createdAt != null) {
            metadata.put("createdAt", createdAt.toEpochMilli());
        }
        if (conversationId != null) {
            metadata.put("conversationId", conversationId);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            metadata.put(extra[i], extra[i + 1]);
        }
        return metadata;
    }
}
