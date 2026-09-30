package org.dual.replicate.search.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link VectorStore} di Spring AI su H2 (tabella {@code VECTOR_DOC}, V19): nessun servizio esterno. I documenti stanno in
 * tabella (durabilita', un solo file col resto dei dati) e in una mappa in memoria da cui si legge: la ricerca e' un
 * prodotto scalare su vettori normalizzati (coseno), lineare nel numero di documenti: adatta a decine di migliaia di righe
 * (con 384 dimensioni sono ~1,5 KB a documento). Se un giorno non bastasse, basta un altro bean {@code VectorStore}
 * (Elasticsearch, Qdrant...): chi lo usa dipende solo dall'interfaccia.
 *
 * <p>Modelli e5: i testi da indicizzare vanno prefissati {@code "passage: "} e le query {@code "query: "} (senza, la qualita'
 * crolla); il prefisso e' interno allo store, chi lo usa passa il testo nudo. {@code embeddingModelId} identifica il modello:
 * un documento con un modello diverso da quello corrente viene ri-embeddato al prossimo {@link #add}.
 *
 * <p>Ogni documento porta in {@code metadata} almeno {@code type} (stringa) e {@code refId} (numero); {@code conversationId} e'
 * opzionale. I filtri ({@link SearchRequest#getFilterExpression()}) supportano EQ, NE, IN, NIN, AND, OR, NOT e ISNULL/ISNOTNULL
 * sulle chiavi dei metadata; gli altri operatori lanciano {@link UnsupportedOperationException}.
 */
public class H2VectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(H2VectorStore.class);

    static final String PASSAGE_PREFIX = "passage: ";
    static final String QUERY_PREFIX = "query: ";
    private static final int BATCH = 32;

    private record Entry(String id, String content, Map<String, Object> metadata, float[] vector, String model, String hash,
                         Instant updatedAt) {
    }

    /** Un documento cosi' come sta nello store, per l'interfaccia di amministrazione (senza il vettore). */
    public record StoredDocument(String id, String type, Long refId, Long conversationId, String content,
                                 Map<String, Object> metadata, String model, String hash, Instant updatedAt, int dimensions) {
    }

    /** Una pagina di {@link StoredDocument} (numerazione da 1). */
    public record Listing(List<StoredDocument> documents, int page, int totalPages, long total) {
        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages;
        }
    }

    private final JdbcClient jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final String embeddingModelId;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public H2VectorStore(JdbcClient jdbc, EmbeddingModel embeddingModel, ObjectMapper objectMapper, String embeddingModelId) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.embeddingModelId = embeddingModelId;
    }

    // --- VectorStore ----------------------------------------------------------------------------------------------

    @Override
    public synchronized void add(List<Document> documents) {
        ensureLoaded();
        List<Document> todo = new ArrayList<>();
        for (Document document : documents) {
            requireMetadata(document);
            Entry existing = entries.get(document.getId());
            if (existing == null || !existing.hash().equals(hash(document.getText())) || !embeddingModelId.equals(existing.model())) {
                todo.add(document);
            } else if (!sameMetadata(existing.metadata(), document.getMetadata())) {
                upsert(document, existing.vector()); // solo i metadata sono cambiati: il vettore resta valido
            }
        }
        for (int from = 0; from < todo.size(); from += BATCH) {
            List<Document> batch = todo.subList(from, Math.min(todo.size(), from + BATCH));
            List<float[]> vectors = embeddingModel.embed(batch.stream().map(d -> PASSAGE_PREFIX + d.getText()).toList());
            for (int i = 0; i < batch.size(); i++) {
                upsert(batch.get(i), normalize(vectors.get(i)));
            }
        }
    }

    @Override
    public synchronized void delete(List<String> ids) {
        ensureLoaded();
        for (int from = 0; from < ids.size(); from += 500) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            jdbc.sql("delete from VECTOR_DOC where ID in (:ids)").param("ids", chunk).update();
            chunk.forEach(entries::remove);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        ensureLoaded();
        delete(entries.values().stream().filter(e -> matches(filterExpression, e.metadata())).map(Entry::id).toList());
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        ensureLoaded();
        float[] query = normalize(embeddingModel.embed(QUERY_PREFIX + request.getQuery()));
        Filter.Expression filter = request.getFilterExpression();
        double threshold = request.getSimilarityThreshold();
        int topK = Math.max(1, request.getTopK());

        // min-heap di dimensione topK: in cima il peggiore dei migliori.
        PriorityQueue<Map.Entry<Entry, Double>> best = new PriorityQueue<>(Map.Entry.comparingByValue());
        for (Entry entry : entries.values()) {
            if (entry.vector().length != query.length || (filter != null && !matches(filter, entry.metadata()))) {
                continue;
            }
            double score = dot(query, entry.vector());
            if (score < threshold) {
                continue;
            }
            if (best.size() < topK) {
                best.add(Map.entry(entry, score));
            } else if (score > best.peek().getValue()) {
                best.poll();
                best.add(Map.entry(entry, score));
            }
        }
        return best.stream()
                .sorted(Map.Entry.<Entry, Double>comparingByValue().reversed())
                .map(e -> Document.builder().id(e.getKey().id()).text(e.getKey().content())
                        .metadata(e.getKey().metadata()).score(e.getValue()).build())
                .toList();
    }

    // --- per chi indicizza ------------------------------------------------------------------------------------------

    /** Gli id dei documenti di un {@code type} (per rimuovere quelli la cui riga sorgente non esiste piu'). */
    public List<String> idsOfType(String type) {
        ensureLoaded();
        return entries.values().stream().filter(e -> type.equals(e.metadata().get("type"))).map(Entry::id).toList();
    }

    /** Il documento {@code id}, se c'e'. */
    public java.util.Optional<StoredDocument> find(String id) {
        ensureLoaded();
        return java.util.Optional.ofNullable(entries.get(id)).map(H2VectorStore::stored);
    }

    /** Una pagina (da 1) dei documenti di {@code type} ({@code null} = tutti), piu' recenti prima. */
    public Listing list(String type, int page, int size) {
        ensureLoaded();
        List<Entry> matching = entries.values().stream()
                .filter(e -> type == null || type.equals(e.metadata().get("type")))
                .sorted(java.util.Comparator.comparing(Entry::updatedAt).reversed().thenComparing(Entry::id))
                .toList();
        int totalPages = Math.max(1, (int) Math.ceil(matching.size() / (double) size));
        int current = Math.min(Math.max(1, page), totalPages);
        List<StoredDocument> documents = matching.stream().skip((long) (current - 1) * size).limit(size)
                .map(H2VectorStore::stored).toList();
        return new Listing(documents, current, totalPages, matching.size());
    }

    /** Quanti documenti per {@code type}. */
    public Map<String, Long> countsByType() {
        ensureLoaded();
        Map<String, Long> counts = new java.util.TreeMap<>();
        entries.values().forEach(e -> counts.merge(String.valueOf(e.metadata().get("type")), 1L, Long::sum));
        return counts;
    }

    /** Ricalcola l'embedding di {@code id} anche se testo e modello non sono cambiati. {@code false} se non esiste. */
    public synchronized boolean reembed(String id) {
        ensureLoaded();
        Entry entry = entries.get(id);
        if (entry == null) {
            return false;
        }
        Document document = Document.builder().id(id).text(entry.content()).metadata(entry.metadata()).build();
        upsert(document, normalize(embeddingModel.embed(PASSAGE_PREFIX + entry.content())));
        return true;
    }

    /** L'id del modello di embedding corrente (l'URI ONNX) e la dimensione dei vettori. */
    public String embeddingModelId() {
        return embeddingModelId;
    }

    public int dimensions() {
        ensureLoaded();
        return entries.values().stream().findFirst().map(e -> e.vector().length).orElseGet(embeddingModel::dimensions);
    }

    private static StoredDocument stored(Entry e) {
        Object conversationId = e.metadata().get("conversationId");
        return new StoredDocument(e.id(), String.valueOf(e.metadata().get("type")),
                ((Number) e.metadata().get("refId")).longValue(),
                conversationId instanceof Number n ? n.longValue() : null, e.content(), e.metadata(), e.model(), e.hash(),
                e.updatedAt(), e.vector().length);
    }

    public int size() {
        ensureLoaded();
        return entries.size();
    }

    // --- persistenza ------------------------------------------------------------------------------------------------

    private void upsert(Document document, float[] vector) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        String hash = hash(document.getText());
        Object conversationId = metadata.get("conversationId");
        Instant now = Instant.now();
        jdbc.sql("""
                merge into VECTOR_DOC (ID, TYPE, REF_ID, CONVERSATION_ID, CONTENT, METADATA, EMBEDDING, EMBEDDING_MODEL, CONTENT_HASH, UPDATED_AT)
                key (ID) values (:id, :type, :refId, :conversationId, :content, :metadata, :embedding, :model, :hash, :updatedAt)
                """)
                .param("id", document.getId())
                .param("type", String.valueOf(metadata.get("type")))
                .param("refId", ((Number) metadata.get("refId")).longValue())
                .param("conversationId", conversationId instanceof Number n ? n.longValue() : null, java.sql.Types.BIGINT)
                .param("content", document.getText())
                .param("metadata", objectMapper.writeValueAsString(metadata))
                .param("embedding", toBytes(vector))
                .param("model", embeddingModelId)
                .param("hash", hash)
                .param("updatedAt", Timestamp.from(now))
                .update();
        entries.put(document.getId(), new Entry(document.getId(), document.getText(), metadata, vector, embeddingModelId, hash, now));
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        jdbc.sql("select ID, CONTENT, METADATA, EMBEDDING, EMBEDDING_MODEL, CONTENT_HASH, UPDATED_AT from VECTOR_DOC").query(rs -> {
            Map<String, Object> metadata = objectMapper.readValue(rs.getString("METADATA"), new TypeReference<Map<String, Object>>() {
            });
            entries.put(rs.getString("ID"), new Entry(rs.getString("ID"), rs.getString("CONTENT"), metadata,
                    fromBytes(rs.getBytes("EMBEDDING")), rs.getString("EMBEDDING_MODEL"), rs.getString("CONTENT_HASH"),
                    rs.getTimestamp("UPDATED_AT").toInstant()));
        });
        loaded = true;
        log.info("Vector store: {} documenti caricati", entries.size());
    }

    // --- filtri -----------------------------------------------------------------------------------------------------

    static boolean matches(Filter.Operand operand, Map<String, Object> metadata) {
        if (operand instanceof Filter.Group group) {
            return matches(group.content(), metadata);
        }
        Filter.Expression expression = (Filter.Expression) operand;
        return switch (expression.type()) {
            case AND -> matches(expression.left(), metadata) && matches(expression.right(), metadata);
            case OR -> matches(expression.left(), metadata) || matches(expression.right(), metadata);
            case NOT -> !matches(expression.left(), metadata);
            case EQ -> same(valueOf(expression.left(), metadata), valueOf(expression.right(), metadata));
            case NE -> !same(valueOf(expression.left(), metadata), valueOf(expression.right(), metadata));
            case IN -> inList(valueOf(expression.left(), metadata), valueOf(expression.right(), metadata));
            case NIN -> !inList(valueOf(expression.left(), metadata), valueOf(expression.right(), metadata));
            case ISNULL -> valueOf(expression.left(), metadata) == null;
            case ISNOTNULL -> valueOf(expression.left(), metadata) != null;
            default -> throw new UnsupportedOperationException("Operatore di filtro non supportato: " + expression.type());
        };
    }

    private static Object valueOf(Filter.Operand operand, Map<String, Object> metadata) {
        if (operand instanceof Filter.Key key) {
            return metadata.get(key.key());
        }
        if (operand instanceof Filter.Value value) {
            return value.value();
        }
        throw new UnsupportedOperationException("Operando di filtro non supportato: " + operand);
    }

    private static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return a.toString().equals(b.toString());
    }

    /** Numeri da JSON (Integer) e da codice (Long) sono lo stesso valore: si confrontano per valore, non per tipo. */
    private static boolean sameMetadata(Map<String, Object> a, Map<String, Object> b) {
        return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(k -> same(a.get(k), b.get(k)));
    }

    private static boolean inList(Object value, Object list) {
        return list instanceof Collection<?> values && values.stream().anyMatch(v -> same(value, v));
    }

    // --- vettori ----------------------------------------------------------------------------------------------------

    private static void requireMetadata(Document document) {
        if (!(document.getMetadata().get("type") instanceof String) || !(document.getMetadata().get("refId") instanceof Number)) {
            throw new IllegalArgumentException("Il documento " + document.getId() + " richiede i metadata 'type' (stringa) e 'refId' (numero)");
        }
        if (document.getText() == null || document.getText().isBlank()) {
            throw new IllegalArgumentException("Il documento " + document.getId() + " non ha testo");
        }
    }

    static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = norm == 0 ? 0 : (float) (vector[i] / norm);
        }
        return result;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    private static float[] fromBytes(byte[] bytes) {
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
