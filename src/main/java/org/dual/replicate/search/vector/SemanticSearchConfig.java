package org.dual.replicate.search.vector;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
     * L'id del modello (l'URI ONNX configurato) e' salvato con ogni documento: cambiare modello => al prossimo giro
     * {@link ArchiveIndexService} ri-embedda tutto, senza migrazioni.
     */
    @Bean
    H2VectorStore vectorStore(JdbcClient jdbc, EmbeddingModel embeddingModel, ObjectMapper objectMapper,
                              @Value("${spring.ai.embedding.transformer.onnx.model-uri}") String modelId) {
        return new H2VectorStore(jdbc, embeddingModel, objectMapper, modelId);
    }
}
