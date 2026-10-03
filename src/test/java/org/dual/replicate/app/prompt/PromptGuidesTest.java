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

    /** La guida dell'inpainting codifica le pratiche (solo il contenuto della zona, niente negazioni, trigger word intatta): una modifica non deve perderle. */
    @Test
    void inpaintGuideKeepsTheInpaintingPractices() {
        String guide = env.getRequiredProperty("generateForm.inpaint-prompt-enhancement-guide");

        assertThat(guide).contains("ONLY what goes inside the painted region").contains("Never describe the rest of the scene")
                .contains("negative instruction").contains("trigger word").contains("verbatim");
    }

    /** La guida img2img descrive il risultato (non un'istruzione), legge prompt_strength e conserva la trigger word. */
    @Test
    void img2imgGuideReadsThePromptStrengthAndKeepsTheTriggerWord() {
        String guide = env.getRequiredProperty("generateForm.img2img-prompt-enhancement-guide");

        assertThat(guide).contains("FINAL image").contains("prompt_strength").contains("LOW strength").contains("HIGH strength")
                .contains("negative instruction").contains("trigger word").contains("verbatim");
    }

    @Test
    void everyEnhancementGuideEmbedsTheSharedCreativeContext() {
        String context = env.getRequiredProperty("prompts.creative-context");
        assertThat(context).contains("never anything involving minors");

        for (String key : new String[]{"generateForm.prompt-enhancement-guide", "generateForm.video-prompt-enhancement-guide",
                "generateForm.edit-prompt-enhancement-guide", "generateForm.inpaint-prompt-enhancement-guide",
                "generateForm.img2img-prompt-enhancement-guide"}) {
            assertThat(env.getRequiredProperty(key)).as(key).contains(context).doesNotContain("${");
        }
    }
}
