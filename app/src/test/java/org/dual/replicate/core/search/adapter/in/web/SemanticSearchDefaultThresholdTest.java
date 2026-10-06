package org.dual.replicate.core.search.adapter.in.web;

import org.dual.replicate.core.search.adapter.out.vector.FakeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La soglia di somiglianza minima di DEFAULT di /search (da application.yml, non sovrascritta): 80%, proposta nella form e nei link di paginazione. */
@SpringBootTest(properties = "app.search.enabled=true")
@AutoConfigureMockMvc
@Import(SemanticSearchDefaultThresholdTest.FakeEmbedding.class)
class SemanticSearchDefaultThresholdTest {

    @TestConfiguration
    static class FakeEmbedding {
        @Bean
        @Primary
        EmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theFormProposesEightyPercentUnlessTheUserChoosesOtherwise() throws Exception {
        String page = mockMvc.perform(get("/search")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String explicit = mockMvc.perform(get("/search").param("threshold", "30")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).containsPattern("name=\"threshold\"[^>]*value=\"80\"");
        assertThat(explicit).containsPattern("name=\"threshold\"[^>]*value=\"30\"");
    }
}
