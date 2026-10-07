package org.hexa.core.search.adapter.out.vector;

import org.hexa.core.search.port.out.IVectorIndex;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Cabla lo store semantico. Attivo solo con {@code app.search.enabled=true} (default; i test lo disattivano insieme
 * all'{@code EmbeddingModel} locale, che scaricherebbe il modello). L'{@code EmbeddingModel} viene dall'autoconfigurazione
 * {@code spring.ai.model.embedding=transformers} (modello ONNX locale, vedi application.yml).
 */
@Configuration
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
class SemanticSearchConfig {

    /**
     * {@code PgVectorStore} sulla tabella {@code vector_store} creata da Flyway (V1, {@code initializeSchema=false}). Id testuali
     * ("generation:12"); nessun indice ANN: la ricerca e' esatta e restituisce TUTTA la classifica sopra soglia (vedi V1).
     * L'{@code EmbeddingModel} e' avvolto per i prefissi e5 ({@link E5PrefixEmbeddingModel}).
     */
    @Bean
    VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return pgVectorStore(jdbcTemplate, embeddingModel);
    }

    static PgVectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, new E5PrefixEmbeddingModel(embeddingModel))
                .vectorTableName("vector_store")
                .idType(PgVectorStore.PgIdType.TEXT)
                .dimensions(VectorIndexer.DIMENSIONS)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .initializeSchema(false)
                .build();
    }

    @Bean
    VectorDocumentRepository vectorDocumentRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new VectorDocumentRepository(jdbc, objectMapper);
    }

    /**
     * L'id del modello (l'URI ONNX configurato) e' salvato con ogni documento: cambiare modello => al prossimo giro
     * la riconciliazione dell'indice ri-embedda tutto, senza migrazioni (a parita' di dimensioni, vedi V1).
     */
    @Bean
    VectorIndexer vectorIndexer(VectorStore vectorStore, VectorDocumentRepository repository,
                                @Value("${spring.ai.embedding.transformer.onnx.model-uri}") String modelId) {
        return new VectorIndexer(vectorStore, repository, modelId);
    }

    @Bean
    IVectorIndex vectorIndex(VectorStore vectorStore, VectorIndexer indexer, VectorDocumentRepository repository) {
        return new PgVectorIndex(vectorStore, indexer, repository);
    }
}
