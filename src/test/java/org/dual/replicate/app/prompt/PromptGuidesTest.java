package org.dual.replicate.app.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le guide dell'"AI enhance" e il system prompt della chat condividono UNA clausola di contesto creativo
 * ({@code prompts.creative-context}, prompts.properties): qui si prova che il placeholder si risolva in ogni guida, cosi' l'enhancer
 * non puo' tornare a essere piu' restrittivo della chat senza che un test se ne accorga.
 */
@SpringBootTest
class PromptGuidesTest {

    @Autowired
    Environment env;

    @Test
    void everyEnhancementGuideEmbedsTheSharedCreativeContext() {
        String context = env.getRequiredProperty("prompts.creative-context");
        assertThat(context).contains("never anything involving minors");

        for (String key : new String[]{"generateForm.prompt-enhancement-guide", "generateForm.video-prompt-enhancement-guide",
                "generateForm.edit-prompt-enhancement-guide"}) {
            assertThat(env.getRequiredProperty(key)).as(key).contains(context).doesNotContain("${");
        }
    }
}
