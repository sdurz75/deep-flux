package org.dual.replicate.controller;

import java.util.ArrayList;
import java.util.List;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Usa configurazione": /generations/new?config=&lt;id&gt; compila il form dai dati salvati; il dettaglio e la chat portano il link. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReuseConfigRenderingTests {

    private static final String DEV_LORA = "black-forest-labs/flux-dev-lora";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IGenerationStore repository;

    private Generation saved(String model, String prompt, String parametersJson, Long seed, GenerationKind kind, String... files) {
        Generation generation = new Generation("pred-reuse-" + System.nanoTime(), model, null, prompt, parametersJson, seed);
        generation.setKind(kind);
        generation.setStatus(files.length == 0 ? GenerationStatus.FAILED : GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(new ArrayList<>(List.of(files)));
        return repository.save(generation);
    }

    private String page(String... params) throws Exception {
        var request = get("/generations/new");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void configOfAFluxDevLoraGenerationFillsModelLoraParametersPromptAndSeed() throws Exception {
        Generation generation = saved(DEV_LORA, "a smiling face, TRIGGER",
                "{\"lora_weights\":\"me/face-lora\",\"lora_scale\":0.7,\"num_inference_steps\":20,\"aspect_ratio\":\"16:9\","
                        + "\"go_fast\":false,\"num_outputs\":2,\"unknown\":\"x\"}",
                4242L, GenerationKind.IMAGE, "a.png", "b.png");

        String body = page("config", String.valueOf(generation.getId()), "file", "a.png");

        assertThat(body).containsPattern("<option value=\"" + DEV_LORA + "\"[^>]*selected");
        assertThat(body).containsPattern("name=\"lora_weights\"[^>]*value=\"me/face-lora\"");
        assertThat(body).containsPattern("name=\"lora_scale\"[^>]*value=\"0.7\"");
        assertThat(body).containsPattern("name=\"num_inference_steps\"[^>]*value=\"20\"");
        // Una rigenerazione per file e' UN file.
        assertThat(body).containsPattern("name=\"num_outputs\"[^>]*value=\"1\"");
        assertThat(body).containsPattern("id=\"param-seed\"[^>]*value=\"4242\"");
        assertThat(body).containsPattern("<textarea[^>]*id=\"prompt\"[^>]*>a smiling face, TRIGGER</textarea>");
        // La form arriva da un link esplicito: lo stato salvato nel browser non deve riscriverla.
        assertThat(body).contains("data-persist-fresh=\"true\"").contains("data-persist-key=\"generate.image\"");
        // La version non si spinge mai: il campo resta vuoto e il servizio usa quella del catalogo.
        assertThat(body).doesNotContainPattern("name=\"version\"[^>]*value=\"[^\"]");
    }

    @Test
    void configOfAVideoOpensTheVideoPageWithItsValidSource() throws Exception {
        Generation source = saved("owner/model", "a cat", null, null, GenerationKind.IMAGE, "src.png");
        Generation video = saved("prunaai/p-video", "a cat walks", "{\"duration\":7}", 9L, GenerationKind.VIDEO, "v.mp4");
        video.setSourceGenerationId(source.getId());
        video.setSourceImageFilename("src.png");
        repository.save(video);

        String body = page("config", String.valueOf(video.getId()));

        assertThat(body).containsPattern("<option value=\"prunaai/p-video\"[^>]*selected");
        assertThat(body).contains("data-persist-key=\"generate.video\"").contains("data-persist-fresh=\"true\"");
        assertThat(body).containsPattern("name=\"sourceGenerationId\"[^>]*value=\"" + source.getId() + "\"");
        assertThat(body).containsPattern("name=\"sourceImage\"[^>]*value=\"src.png\"");
        assertThat(body).containsPattern("name=\"duration\"[^>]*value=\"7\"");
    }

    @Test
    void configOfAnInpaintingGenerationOpensTheImagePage() throws Exception {
        Generation fill = saved("black-forest-labs/flux-fill-dev", "a smiling face", "{\"lora_weights\":\"me/face\"}", 5L,
                GenerationKind.IMAGE, "f.png");

        String body = page("config", String.valueOf(fill.getId()));

        assertThat(body).contains("data-persist-key=\"generate.image\"");
        assertThat(body).containsPattern("<option value=\"black-forest-labs/flux-fill-dev\"[^>]*selected");
        assertThat(body).containsPattern("name=\"lora_weights\"[^>]*value=\"me/face\"");
    }

    @Test
    void anUnknownGenerationOrAnUnavailableModelShowTheReasonOnTheNormalPage() throws Exception {
        String unknown = page("config", "999999");
        assertThat(unknown).contains("Generazione da riproporre non trovata.").doesNotContain("data-persist-fresh=\"true\"");

        Generation old = saved("owner/model", "x", "{}", 1L, GenerationKind.IMAGE, "o.png");
        String unavailable = page("config", String.valueOf(old.getId()));
        assertThat(unavailable).contains("Modello &quot;owner/model&quot; non piu&#39; disponibile").doesNotContain("data-persist-fresh=\"true\"");
    }

    /** Il dettaglio NON passa `file`: la rigenerazione per file (num_outputs=1) e' della chat, qui resta il numero di uscite originale. */
    @Test
    void theDetailLinksToTheFullConfigurationWithoutAFileAndKeepsNumOutputs() throws Exception {
        Generation batch = saved(DEV_LORA, "p", "{\"num_outputs\":3}", 1L, GenerationKind.IMAGE, "x.png", "y.png", "z.png");
        Generation empty = saved(DEV_LORA, "p", "{}", null, GenerationKind.IMAGE);
        empty.setStatus(GenerationStatus.SUCCEEDED);
        empty = repository.save(empty);

        String detail = mockMvc.perform(get("/generations/" + batch.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String emptyDetail = mockMvc.perform(get("/generations/" + empty.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(detail).contains("href=\"/generations/new?config=" + batch.getId() + "\"").contains("Usa configurazione")
                .doesNotContain("new?config=" + batch.getId() + "&amp;file");
        assertThat(emptyDetail).contains("href=\"/generations/new?config=" + empty.getId() + "\"");

        // Seguendo il link: tutte le uscite originali e il seed della prima immagine.
        String form = page("config", String.valueOf(batch.getId()));
        assertThat(form).containsPattern("name=\"num_outputs\"[^>]*value=\"3\"").containsPattern("id=\"param-seed\"[^>]*value=\"1\"");
    }

    /** `data-persist-fresh` salta il restore: deve esserci SOLO sulla pagina compilata da un link valido, mai (nemmeno vuoto) altrove. */
    @Test
    void persistFreshIsAbsentFromEveryOtherPage() throws Exception {
        String bareAttribute = "<form[^>]*data-persist-fresh";
        assertThat(page()).doesNotContainPattern(bareAttribute);
        assertThat(page("kind", "video")).doesNotContainPattern(bareAttribute);
        assertThat(page("kind", "edit")).doesNotContainPattern(bareAttribute);
        assertThat(page("config", "999999")).doesNotContainPattern(bareAttribute);
        Generation old = saved("owner/model", "x", "{}", 1L, GenerationKind.IMAGE, "o.png");
        assertThat(page("config", String.valueOf(old.getId()))).doesNotContainPattern(bareAttribute);
    }

    @Test
    void theChatRegenerateButtonOpensTheConfigLinkInsteadOfWritingTheSharedSlot() throws Exception {
        String chat = mockMvc.perform(get("/deep-chat")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        chat = mockMvc.perform(get(chat)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(chat).contains("'?config='").doesNotContain("sharedPrompt").doesNotContain("sharedSeed");
    }
}
