package org.dual.replicate.app.generation.adapter.out.search;

import java.util.List;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.port.in.ILoraPresets.LoraView;
import org.dual.replicate.core.search.domain.DocumentTypes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationSearchTextTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static Generation generation(String model, String prompt, String parametersJson, GenerationKind kind) {
        Generation generation = new Generation("ext", model, null, prompt, parametersJson);
        generation.setKind(kind);
        return generation;
    }

    @Test
    void promptComesFirstFollowedByTheIndexTags() {
        Generation generation = generation("black-forest-labs/flux-krea-dev", "un gatto", "{\"aspect_ratio\":\"9:16\"}", GenerationKind.IMAGE);

        String text = GenerationSearchText.of(generation, List.of(), mapper);

        assertThat(text).startsWith("un gatto" + DocumentTypes.TAGS_SEPARATOR).contains("foto").contains("flux-krea-dev")
                .contains("verticale").doesNotContain("video");
        assertThat(DocumentTypes.visibleText(text)).isEqualTo("un gatto");
    }

    @Test
    void videosAreTaggedAsSuchAndDerivedOnesAsAnimations() {
        Generation generation = generation("prunaai/p-video", "una barca", "{\"resolution\":\"720p\"}", GenerationKind.VIDEO);
        generation.setSourceGenerationId(7L);

        String text = GenerationSearchText.of(generation, List.of(), mapper);

        assertThat(text).contains("video").contains("720p").contains("animazione").doesNotContain("foto");
    }

    @Test
    void orientationComesFromAspectRatioOrWidthAndHeight() {
        assertThat(GenerationSearchText.tags(generation("a/b", "p", "{\"aspect_ratio\":\"16:9\"}", GenerationKind.IMAGE), List.of(), mapper))
                .anyMatch(t -> t.contains("orizzontale"));
        assertThat(GenerationSearchText.tags(generation("a/b", "p", "{\"aspect_ratio\":\"1:1\"}", GenerationKind.IMAGE), List.of(), mapper))
                .anyMatch(t -> t.contains("quadrato"));
        assertThat(GenerationSearchText.tags(generation("a/b", "p", "{\"width\":512,\"height\":768}", GenerationKind.IMAGE), List.of(), mapper))
                .anyMatch(t -> t.contains("verticale"));
        assertThat(GenerationSearchText.tags(generation("a/b", "p", "{\"seed\":1}", GenerationKind.IMAGE), List.of(), mapper))
                .noneMatch(t -> t.contains("verticale") || t.contains("orizzontale") || t.contains("quadrato"));
    }

    @Test
    void loraNamesAndPresetTriggerWordsAreIncludedButSeedsAndStepsAreNot() {
        Generation generation = generation("black-forest-labs/flux-dev-lora", "ritratto",
                "{\"lora_weights\":\"owner/anime-style\",\"extra_lora\":\"https://x/y/detail.safetensors?dl=1\",\"num_inference_steps\":28,\"seed\":123}",
                GenerationKind.IMAGE);
        LoraView preset = new LoraView(1L, "Anime", "owner/anime-style", 1.0, "anime, cel shading", null);

        String text = GenerationSearchText.of(generation, List.of(preset), mapper);

        assertThat(text).contains("LoRA anime-style").contains("LoRA detail").contains("Anime").contains("cel shading")
                .doesNotContain("28").doesNotContain("123");
    }

    @Test
    void invalidParametersJsonIsToleratedAndALongPromptYieldsRoomToTheTags() {
        Generation broken = generation("a/b", "p", "{non json", GenerationKind.IMAGE);
        assertThat(GenerationSearchText.of(broken, List.of(), mapper)).startsWith("p" + DocumentTypes.TAGS_SEPARATOR);

        Generation longPrompt = generation("a/b", "x".repeat(5000), "{\"aspect_ratio\":\"3:4\"}", GenerationKind.IMAGE);
        String text = GenerationSearchText.of(longPrompt, List.of(), mapper);
        assertThat(text.length()).isLessThanOrEqualTo(DocumentTypes.MAX_CHARS);
        assertThat(text).contains("verticale");
    }

    @Test
    void shortNameStripsPathQueryAndExtension() {
        assertThat(GenerationSearchText.shortName("owner/nome")).isEqualTo("nome");
        assertThat(GenerationSearchText.shortName("https://civitai.com/api/download/models/9?type=Model")).isEqualTo("9");
        assertThat(GenerationSearchText.shortName("https://hf.co/x/Detail.SAFETENSORS")).isEqualTo("Detail");
    }

    /** Un'immagine importata e' indicizzata per la descrizione e per le tag dell'analisi, con la provenienza; senza modello ne' parametri. */
    @Test
    void anImportedImageIsIndexedByItsDescriptionAndAnalysisTags() {
        Generation generation = Generation.imported("a.png", java.time.Instant.parse("2026-10-04T10:00:00Z"));
        generation.applyAnalysis("Un gatto rosso su un divano.", List.of("gatto", "cat", "divano"));

        String text = GenerationSearchText.of(generation, List.of(), mapper);

        assertThat(DocumentTypes.visibleText(text)).isEqualTo("Un gatto rosso su un divano.");
        assertThat(text).contains("immagine importata").contains("imported image").contains("gatto").contains("cat").contains("foto");
        assertThat(text).doesNotContain("null");
    }
}
