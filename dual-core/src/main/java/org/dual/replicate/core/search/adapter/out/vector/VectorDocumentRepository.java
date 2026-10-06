package org.dual.replicate.core.search.adapter.out.vector;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.dual.replicate.core.search.domain.IndexedDocument;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lettura (e ritocco dei soli metadata) della tabella {@code vector_store} per tutto cio' che l'interfaccia Spring AI
 * {@code VectorStore} non offre: sfogliare, contare, ispezionare un documento, sapere cosa e' gia' indicizzato. La ricerca per
 * significato e la scrittura dei documenti (che richiedono l'embedding) restano al {@code VectorStore}; questa classe non embedda mai.
 *
 * <p>I metadata riservati scritti da {@link VectorIndexer} ({@code contentHash}, {@code embeddingModel}, {@code indexedAt})
 * sostituiscono le colonne che aveva il vecchio store su H2. I filtri {@link Filter.Expression} sono tradotti con lo stesso
 * convertitore (jsonpath) di {@code PgVectorStore}: stessi operatori supportati (EQ, NE, IN, NIN, AND, OR, GT/GTE/LT/LTE).
 */
public class VectorDocumentRepository {

    static final String HASH = "contentHash";
    static final String MODEL = "embeddingModel";
    static final String INDEXED_AT = "indexedAt";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, content, metadata::text as metadata, vector_dims(embedding) as dimensions";
    /** Piu' recenti prima: {@code createdAt} del contenuto, o l'indicizzazione se il documento non lo porta. */
    private static final String RECENT_FIRST = "coalesce((metadata->>'createdAt')::bigint, (metadata->>'indexedAt')::bigint, 0) desc, id";

    /** Una pagina di {@link IndexedDocument} (numerazione da 1). */
    public record Listing(List<IndexedDocument> documents, int page, int totalPages, long total) {
        public boolean hasPrevious() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages;
        }
    }

    /** Cosa risulta gia' indicizzato di un documento: serve a {@link VectorIndexer} per saltare gli invariati. */
    record Indexed(String hash, String model, Map<String, Object> metadata) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final PgVectorFilterExpressionConverter filters = new PgVectorFilterExpressionConverter();

    public VectorDocumentRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Il documento {@code id}, se c'e'. */
    public Optional<IndexedDocument> find(String id) {
        return jdbc.sql("select " + COLUMNS + " from vector_store where id = :id").param("id", id)
                .query((rs, row) -> stored(rs.getString("id"), rs.getString("content"), rs.getString("metadata"), rs.getInt("dimensions")))
                .optional();
    }

    /** Una pagina (da 1) dei documenti che soddisfano {@code filter} ({@code null} = tutti), piu' recenti prima. */
    public Listing list(Filter.Expression filter, int page, int size) {
        String where = filter == null ? "" : " where " + filters.convertExpression(filter);
        long total = jdbc.sql("select count(*) from vector_store" + where).query(Long.class).single();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        int current = Math.min(Math.max(1, page), totalPages);
        List<IndexedDocument> documents = jdbc.sql("select " + COLUMNS + " from vector_store" + where + " order by " + RECENT_FIRST
                        + " limit :limit offset :offset")
                .param("limit", size).param("offset", (long) (current - 1) * size)
                .query((rs, row) -> stored(rs.getString("id"), rs.getString("content"), rs.getString("metadata"), rs.getInt("dimensions")))
                .list();
        return new Listing(documents, current, totalPages, total);
    }

    /** Gli id dei documenti di un {@code type} (per rimuovere quelli la cui riga sorgente non esiste piu'). */
    public List<String> idsOfType(String type) {
        return jdbc.sql("select id from vector_store where metadata->>'type' = :type").param("type", type).query(String.class).list();
    }

    /** Quanti documenti per {@code type}. */
    public Map<String, Long> countsByType() {
        Map<String, Long> counts = new TreeMap<>();
        jdbc.sql("select coalesce(metadata->>'type', 'null') as type, count(*) as n from vector_store group by 1")
                .query((rs, row) -> counts.put(rs.getString("type"), rs.getLong("n"))).list();
        return counts;
    }

    /** I tag utente usati dai documenti (metadata {@code tags}) con quanti documenti li portano, i piu' usati prima. */
    public Map<String, Long> tagCounts() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        jdbc.sql("select t as tag, count(*) as n from vector_store, jsonb_array_elements_text(metadata::jsonb->'tags') t "
                        + "where jsonb_typeof(metadata::jsonb->'tags') = 'array' group by t order by n desc, t")
                .query((rs, row) -> counts.put(rs.getString("tag"), rs.getLong("n"))).list();
        return counts;
    }

    public long count() {
        return jdbc.sql("select count(*) from vector_store").query(Long.class).single();
    }

    /** Hash del testo, modello e metadata di quelli, fra {@code ids}, che sono gia' nello store. */
    Map<String, Indexed> indexed(Collection<String> ids) {
        Map<String, Indexed> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("select id, metadata::text as metadata from vector_store where id in (:ids)").param("ids", ids).query(rs -> {
            Map<String, Object> metadata = parse(rs.getString("metadata"));
            result.put(rs.getString("id"), new Indexed((String) metadata.get(HASH), (String) metadata.get(MODEL), metadata));
        });
        return result;
    }

    /** Riscrive i soli metadata (testo ed embedding invariati). */
    void updateMetadata(String id, Map<String, Object> metadata) {
        jdbc.sql("update vector_store set metadata = cast(:metadata as json) where id = :id")
                .param("metadata", objectMapper.writeValueAsString(metadata)).param("id", id).update();
    }

    private IndexedDocument stored(String id, String content, String metadataJson, int dimensions) {
        Map<String, Object> metadata = parse(metadataJson);
        Object conversationId = metadata.get("conversationId");
        Object indexedAt = metadata.get(INDEXED_AT);
        return new IndexedDocument(id, String.valueOf(metadata.get("type")), ((Number) metadata.get("refId")).longValue(),
                conversationId instanceof Number n ? n.longValue() : null, content, metadata,
                String.valueOf(metadata.get(MODEL)), String.valueOf(metadata.get(HASH)),
                indexedAt instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : Instant.EPOCH, dimensions);
    }

    private Map<String, Object> parse(String json) {
        return objectMapper.readValue(json, MAP);
    }
}
