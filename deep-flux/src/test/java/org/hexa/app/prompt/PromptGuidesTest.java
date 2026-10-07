package org.hexa.app.prompt;

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

    /**
     * La guida dell'analisi descrive un'immagine GIA' esistente: i limiti della clausola di generazione (persona reale senza consenso, minori) la
     * facevano rifiutare qualunque foto di una persona. Non deve includerla ne' ripetere quei limiti, e deve chiedere di non rifiutare.
     */
    @Test
    void imageAnalysisGuideDoesNotImportGenerationLimitsAndNeverAsksToRefuse() {
        String guide = env.getRequiredProperty("imageAnalysis.guide");
        String context = env.getRequiredProperty("prompts.creative-context");

        assertThat(guide).doesNotContain("${").doesNotContain(context).doesNotContain("identifiable").doesNotContain("consent")
                .doesNotContain("Never name").contains("DESCRIPTION:").contains("TAGS:").contains("without refusing");
    }

    /**
     * Le guide delle didascalie di addestramento descrivono immagini GIA' esistenti di chi usa l'app (come l'analisi): niente clausola di generazione ne'
     * i suoi limiti, che farebbero rifiutare le foto di persone, e un esplicito "senza rifiutare". Codificano le pratiche dei LoRA: nel soggetto si
     * descrive cio' che NON deve imparare (e si lascia fuori l'identita'), nello stile il contenuto (e non lo stile).
     */
    @Test
    void trainingCaptionGuidesAreForTheOwnersImagesAndKeepTheLoraCaptioningPractices() {
        String context = env.getRequiredProperty("prompts.creative-context");
        String subject = env.getRequiredProperty("trainingCaption.subject-guide");
        String style = env.getRequiredProperty("trainingCaption.style-guide");

        for (String guide : new String[]{subject, style}) {
            assertThat(guide).doesNotContain("${").doesNotContain(context).doesNotContain("identifiable").doesNotContain("consent")
                    .doesNotContain("Never name").contains("without refusing").contains("trigger word").contains("exactly once")
                    .contains("single line").contains("Reply with the caption only");
        }
        assertThat(subject).contains("NOT the subject").contains("permanent identity traits").contains("Do NOT describe");
        assertThat(style).contains("CONTENT").contains("Do NOT describe the style itself");
    }
}
