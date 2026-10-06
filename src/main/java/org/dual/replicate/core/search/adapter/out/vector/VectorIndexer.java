package org.dual.replicate.core.search.adapter.out.vector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import static org.dual.replicate.core.search.adapter.out.vector.VectorDocumentRepository.HASH;
import static org.dual.replicate.core.search.adapter.out.vector.VectorDocumentRepository.INDEXED_AT;
import static org.dual.replicate.core.search.adapter.out.vector.VectorDocumentRepository.MODEL;

/**
 * Scrittura nell'indice semantico: sopra il {@link VectorStore} (che embedda e fa l'upsert) aggiunge cio' che l'interfaccia non
 * ha. Ogni documento porta nei metadata {@code contentHash} (SHA-256 del testo), {@code embeddingModel} (id del modello, l'URI
 * ONNX) e {@code indexedAt}: un documento invariato (stesso testo, stesso modello) non viene ri-embeddato, se cambiano solo i
 * metadata si riscrivono quelli senza toccare il vettore, se cambia il modello si ri-embedda tutto al giro successivo.
 *
 * <p>Ogni documento richiede i metadata {@code type} (stringa) e {@code refId} (numero); {@code conversationId} e'
 * opzionale.
 */
public class VectorIndexer {

    /** Dimensione dei vettori: e' quella della colonna {@code vector_store.embedding} (V1), cioe' di multilingual-e5-small. */
    public static final int DIMENSIONS = 384;

    private static final int BATCH = 32;

    private final VectorStore vectorStore;
    private final VectorDocumentRepository repository;
    private final String embeddingModelId;

    public VectorIndexer(VectorStore vectorStore, VectorDocumentRepository repository, String embeddingModelId) {
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.embeddingModelId = embeddingModelId;
    }

    /** L'id del modello di embedding corrente (l'URI ONNX). */
    public String embeddingModelId() {
        return embeddingModelId;
    }

    /** Aggiunge o aggiorna {@code documents}, ricalcolando l'embedding solo di quelli nuovi o con testo/modello cambiati. */
    public synchronized void upsertIfChanged(List<Document> documents) {
        documents.forEach(VectorIndexer::requireMetadata);
        Map<String, VectorDocumentRepository.Indexed> existing =
                repository.indexed(documents.stream().map(Document::getId).toList());
        List<Document> todo = new ArrayList<>();
        for (Document document : documents) {
            VectorDocumentRepository.Indexed indexed = existing.get(document.getId());
            String hash = hash(document.getText());
            if (indexed == null || !hash.equals(indexed.hash()) || !embeddingModelId.equals(indexed.model())) {
                todo.add(stamped(document, hash));
            } else if (!sameMetadata(indexed.metadata(), document.getMetadata())) {
                repository.updateMetadata(document.getId(), stamped(document, hash).getMetadata()); // il vettore resta valido
            }
        }
        for (int from = 0; from < todo.size(); from += BATCH) {
            vectorStore.add(todo.subList(from, Math.min(todo.size(), from + BATCH)));
        }
    }

    /** Ricalcola l'embedding di {@code id} anche se testo e modello non sono cambiati. {@code false} se non esiste. */
    public synchronized boolean reembed(String id) {
        return repository.find(id).map(doc -> {
            vectorStore.add(List.of(stamped(Document.builder().id(id).text(doc.content()).metadata(doc.metadata()).build(),
                    hash(doc.content()))));
            return true;
        }).orElse(false);
    }

    public synchronized void delete(List<String> ids) {
        for (int from = 0; from < ids.size(); from += 500) {
            vectorStore.delete(ids.subList(from, Math.min(ids.size(), from + 500)));
        }
    }

    /** Il documento con i metadata riservati aggiornati ({@code indexedAt} = ora). */
    private Document stamped(Document document, String hash) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put(HASH, hash);
        metadata.put(MODEL, embeddingModelId);
        metadata.put(INDEXED_AT, System.currentTimeMillis());
        return Document.builder().id(document.getId()).text(document.getText()).metadata(metadata).build();
    }

    private static void requireMetadata(Document document) {
        if (!(document.getMetadata().get("type") instanceof String) || !(document.getMetadata().get("refId") instanceof Number)) {
            throw new IllegalArgumentException("Il documento " + document.getId() + " richiede i metadata 'type' (stringa) e 'refId' (numero)");
        }
        if (document.getText() == null || document.getText().isBlank()) {
            throw new IllegalArgumentException("Il documento " + document.getId() + " non ha testo");
        }
    }

    /** Metadata "di dominio" a confronto (senza i riservati): numeri da JSON (Integer) e da codice (Long) sono lo stesso valore. */
    private static boolean sameMetadata(Map<String, Object> stored, Map<String, Object> wanted) {
        Map<String, Object> current = stored.entrySet().stream().filter(e -> !isReserved(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, Object> next = wanted.entrySet().stream().filter(e -> !isReserved(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return current.keySet().equals(next.keySet()) && current.keySet().stream().allMatch(k -> same(current.get(k), next.get(k)));
    }

    private static boolean isReserved(String key) {
        return HASH.equals(key) || MODEL.equals(key) || INDEXED_AT.equals(key);
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
