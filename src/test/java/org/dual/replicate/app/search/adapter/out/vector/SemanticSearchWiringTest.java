package org.dual.replicate.app.search.adapter.out.vector;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.dual.replicate.app.search.application.ArchiveIndexService;
import org.dual.replicate.app.chat.adapter.ai.ArchiveSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cablaggio REALE come in produzione (config di application.yml: modello ONNX locale, VectorStore su pgvector, tool): opt-in con
 * {@code -Dsemantic.model.test=true} (usa il modello gia' in ./data/models, o lo scarica).
 */
@EnabledIfSystemProperty(named = "semantic.model.test", matches = "true")
@SpringBootTest(properties = {"app.search.enabled=true", "spring.ai.model.embedding=transformers"})
class SemanticSearchWiringTest {

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ArchiveIndexService indexService;
    @Autowired
    private ArchiveSearchTool tool;
    @Autowired
    private IGenerationStore generations;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void wiresTheLocalModelThePgVectorStoreAndTheToolAndFindsBySynonym() {
        assertThat(embeddingModel.dimensions()).isEqualTo(384);
        assertThat(vectorStore).isInstanceOf(org.springframework.ai.vectorstore.pgvector.PgVectorStore.class);

        generations.deleteAll();
        jdbc.sql("delete from vector_store").update();
        for (String prompt : new String[] {"un felino domestico che dorme sul divano", "una macchina sportiva rossa in montagna",
                "ritratto di una donna al tramonto"}) {
            Generation g = new Generation("pred-" + prompt.hashCode(), "owner/model", null, prompt, null);
            g.setStatus(GenerationStatus.SUCCEEDED);
            generations.save(g);
        }
        try {
            indexService.reconcile();

            String result = tool.searchArchive("un gatto che riposa", "generation", null, null, null, null, null);

            System.out.println("WIRING result:\n" + result);
            assertThat(result.lines().findFirst().orElseThrow()).contains("felino domestico");
        } finally {
            generations.deleteAll();
            jdbc.sql("delete from vector_store").update();
        }
    }
}
