package org.dual.hexa.ai.search.adapter.out.vector;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.hexa.ai.chat.adapter.ai.ArchiveSearchTool;
import org.dual.hexa.ai.search.application.ArchiveIndexService;
import org.dual.hexa.ai.search.domain.SearchableDocument;
import org.dual.hexa.ai.search.port.in.ISearchableSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cablaggio REALE come in produzione (config di ai.yml: modello ONNX locale, VectorStore su pgvector, tool): opt-in con
 * {@code -Dsemantic.model.test=true} (usa il modello gia' in ./data/models, o lo scarica). I documenti vengono da una sorgente finta dell'host.
 */
@EnabledIfSystemProperty(named = "semantic.model.test", matches = "true")
@SpringBootTest(properties = {"app.search.enabled=true", "spring.ai.model.embedding=transformers"})
@Import(SemanticSearchWiringTest.Items.class)
class SemanticSearchWiringTest {

    @TestConfiguration
    static class Items {
        @Bean
        ISearchableSource itemSource() {
            return new ISearchableSource() {
                @Override
                public Set<String> types() {
                    return Set.of("item");
                }

                @Override
                public List<SearchableDocument> documents() {
                    return List.of("un felino domestico che dorme sul divano", "una macchina sportiva rossa in montagna", "ritratto di una donna al tramonto")
                            .stream().map(text -> SearchableDocument.of("item:" + Math.abs(text.hashCode()), "item", Math.abs(text.hashCode()), null,
                                    Instant.parse("2026-10-04T10:00:00Z"), text, Map.of())).toList();
                }
            };
        }
    }

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ArchiveIndexService indexService;
    @Autowired
    private ArchiveSearchTool tool;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void wiresTheLocalModelThePgVectorStoreAndTheToolAndFindsBySynonym() {
        assertThat(embeddingModel.dimensions()).isEqualTo(384);
        assertThat(vectorStore).isInstanceOf(org.springframework.ai.vectorstore.pgvector.PgVectorStore.class);

        jdbc.sql("delete from vector_store").update();
        try {
            indexService.reconcile();

            String result = tool.searchArchive("un gatto che riposa", "item", null, null, null, null, null);

            System.out.println("WIRING result:\n" + result);
            assertThat(result.lines().findFirst().orElseThrow()).contains("felino domestico");
        } finally {
            jdbc.sql("delete from vector_store").update();
        }
    }
}
