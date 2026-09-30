package org.dual.replicate.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationKind;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.ChatConversationRepository;
import org.dual.replicate.repository.ChatMessageRepository;
import org.dual.replicate.repository.GenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test che renderizza davvero i nuovi template (contextLoads da
 * solo prova solo il wiring dei bean, non che Thymeleaf risolva
 * fragment/espressioni senza errori a runtime). Copre esplicitamente
 * sia la pagina intera sia la risposta fragment-only (header
 * HX-Request), perche' i due percorsi passano da codice Thymeleaf
 * diverso: un fragment coi parametri restituito come vista diretta
 * richiede parametri nominati, altrimenti va in 500 solo su questo
 * secondo percorso (vedi nota in CLAUDE.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TemplateRenderingTests {

    @Autowired
    private org.thymeleaf.spring6.SpringTemplateEngine templateEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.dual.replicate.repository.AppErrorRepository appErrorRepository;

    @Autowired
    private GenerationRepository repository;

    @Autowired
    private ChatConversationRepository chatConversationRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Test
    void generationFormRenders() throws Exception {
        mockMvc.perform(get("/generations/new")).andExpect(status().isOk());
    }

    /**
     * Regressione: th:case e th:replace sullo STESSO tag in
     * fragments/generation-params.html non filtravano nulla (l'ordine di
     * precedenza degli attributi di Thymeleaf processa th:replace PRIMA
     * di th:switch/th:case), quindi un caricamento pieno di /generations/new
     * (o /deep-chat/{id}, stesso fragment condiviso) concatenava TUTTI E
     * TRE i form-type invece del solo formType corrente - bug presente su
     * ogni singola richiesta, non solo dopo una navigazione (osservato
     * dal vivo con un curl fresco, non solo dopo "esco e torno" come
     * inizialmente segnalato: quella frase descriveva solo QUANDO l'utente
     * se ne era accorto, non la vera condizione di innesco). Il fix
     * annida th:case/th:replace su due <th:block> distinti (vedi il
     * fragment): qui si verifica che, col modello di default (FF3,
     * SORT_ORDER=0), compaiano SOLO i suoi campi (flux_model/lora_scale),
     * mai quelli di klein-9b/krea-dev (go_fast/megapixels), e che
     * "param-seed" (nome ripetuto identico nei tre fragment) appaia
     * esattamente una volta.
     */
    @Test
    void generationFormRendersOnlyTheDefaultModelFieldsOnce() throws Exception {
        String body = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"flux_model\"");
        assertThat(body).doesNotContain("name=\"go_fast\"", "name=\"megapixels\"");
        assertThat(countOccurrences(body, "id=\"param-seed\"")).isEqualTo(1);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /**
     * Nuova validazione introdotta col catalogo modelli in DB (vedi il
     * piano di questa feature): GenerationController.create ora rifiuta
     * un modello non censito PRIMA di chiamare Replicate, invece di
     * accettare qualunque stringa come faceva la vecchia versione
     * "testo libero" del combobox.
     */
    @Test
    void createWithUnknownModelIsRejectedInline() throws Exception {
        String body = mockMvc.perform(post("/generations")
                        .param("model", "owner/does-not-exist")
                        .param("prompt", "a cat"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("owner/does-not-exist");
    }

    /**
     * Endpoint htmx-only scatenato dalla select modello ad ogni cambio
     * (vedi fragments/generation-params.html): deve ritornare i campi
     * del form-type di quel modello, non una pagina intera.
     */
    @Test
    void paramsEndpointRendersFieldsForKnownModel() throws Exception {
        mockMvc.perform(get("/generations/params").param("model", "sdurz75/flux-lora-ff3"))
                .andExpect(status().isOk());
    }

    /** Quarto form-type (P_VIDEO, V12/PVideoParameterHandler): il fragment dedicato renderizza, con i default del modello. */
    @Test
    void paramsEndpointRendersFieldsForPVideo() throws Exception {
        String body = mockMvc.perform(get("/generations/params").param("model", "prunaai/p-video"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"duration\"", "name=\"resolution\"", "name=\"fps\"",
                "name=\"draft\"", "name=\"prompt_upsampling\"");
        assertThat(body).containsPattern("<option value=\"720p\"[^>]*selected");
        assertThat(body).doesNotContainPattern("name=\"prompt_upsampling\"[^>]*checked");
        assertThat(body).doesNotContainPattern("name=\"draft\"[^>]*checked");
    }

    /** /deep-chat propone solo modelli immagine: il modello video non compare nel suo combobox. */
    @Test
    @Transactional
    void deepChatModelSelectExcludesVideoModels() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String chat = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String form = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String videoForm = mockMvc.perform(get("/generations/new").param("kind", "video"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(chat).doesNotContain("prunaai/p-video");
        // Immagini e video non si mescolano: /generations/new elenca solo immagini, ?kind=video solo video.
        assertThat(form).doesNotContain("prunaai/p-video");
        assertThat(videoForm).contains("prunaai/p-video").doesNotContain("black-forest-labs/flux-2-klein-9b");
    }

    /** "Anima": /generations/new?source= preseleziona p-video e porta la sorgente (hidden + anteprima). */
    @Test
    @Transactional
    void newFormWithSourcePreselectsVideoModelAndCarriesTheSource() throws Exception {
        Generation image = new Generation("pred-img", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("1-0.png")));
        image = repository.save(image);

        String body = mockMvc.perform(get("/generations/new").param("source", String.valueOf(image.getId()))
                        .param("sourceImage", "1-0.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("name=\"sourceUpload\"");
        assertThat(body).containsPattern("name=\"sourceGenerationId\"[^>]*value=\"" + image.getId() + "\"");
        assertThat(body).containsPattern("name=\"sourceImage\"[^>]*value=\"1-0.png\"");
        assertThat(body).contains("name=\"duration\"");
        assertThat(body).containsPattern("<option value=\"prunaai/p-video\"[^>]*selected");
        assertThat(body).contains("/images/1-0.png");
    }

    /** Una sorgente non animabile (inesistente) e' ignorata: form normale, nessun hidden. */
    @Test
    void newFormIgnoresUnknownSource() throws Exception {
        String body = mockMvc.perform(get("/generations/new").param("source", "999999"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("name=\"sourceGenerationId\"");
    }

    /** Una sourceImage che non appartiene alla generazione rende la sorgente non valida: ignorata. */
    @Test
    @Transactional
    void newFormIgnoresSourceImageNotBelongingToTheGeneration() throws Exception {
        Generation image = new Generation("pred-img2", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("1-0.png")));
        image = repository.save(image);

        String body = mockMvc.perform(get("/generations/new").param("source", String.valueOf(image.getId()))
                        .param("sourceImage", "other.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("name=\"sourceGenerationId\"");
    }

    /** Dettaglio: un'immagine completata offre "Anima"; un video ha <video controls> e il link alla sorgente, niente "Anima". */
    @Test
    @Transactional
    void detailOffersAnimateForImagesAndRendersVideoForVideos() throws Exception {
        Generation image = new Generation("pred-i", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("2-0.png", "2-1.png")));
        image = repository.save(image);

        Generation video = new Generation("pred-v", "prunaai/p-video", null, "a cat walks", null);
        video.setKind(GenerationKind.VIDEO);
        video.setSourceGenerationId(image.getId());
        video.setStatus(GenerationStatus.SUCCEEDED);
        video.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("3-0.mp4")));
        video = repository.save(video);

        String imageBody = mockMvc.perform(get("/generations/" + image.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String videoBody = mockMvc.perform(get("/generations/" + video.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(imageBody).contains("/generations/new?source=" + image.getId() + "&amp;sourceImage=2-0.png")
                .contains("/generations/new?source=" + image.getId() + "&amp;sourceImage=2-1.png")
                .doesNotContain("<video");
        assertThat(videoBody).containsPattern("<video[^>]*src=\"[^\"]*/images/3-0.mp4\"[^>]*controls")
                .doesNotContain("?source=")
                .contains("/generations/" + image.getId());
    }

    /** Il dettaglio mostra il costo stimato salvato, e nasconde la riga se non c'e' (righe vecchie, modelli senza regola). */
    @Test
    @Transactional
    void detailShowsEstimatedCostOnlyWhenPresent() throws Exception {
        Generation priced = new Generation("pred-p", "black-forest-labs/flux-krea-dev", null, "a cat", null);
        priced.setStatus(GenerationStatus.SUCCEEDED);
        priced.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("4-0.png")));
        priced.setCostUsd(new java.math.BigDecimal("0.120000"));
        priced = repository.save(priced);
        Generation unpriced = new Generation("pred-u", "owner/model", null, "a dog", null);
        unpriced.setStatus(GenerationStatus.SUCCEEDED);
        unpriced.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("5-0.png")));
        unpriced = repository.save(unpriced);

        String withCost = mockMvc.perform(get("/generations/" + priced.getId()).header("Accept-Language", "en"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String italian = mockMvc.perform(get("/generations/" + priced.getId()).header("Accept-Language", "it"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String withoutCost = mockMvc.perform(get("/generations/" + unpriced.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(withCost).contains("Estimated cost").contains("0.1200 USD");
        assertThat(italian).contains("Costo stimato").contains("0,1200 USD");
        assertThat(withoutCost).doesNotContain("Estimated cost").doesNotContain("Costo stimato");
    }

    /** Galleria e listato mostrano un video come <video>, non come <img> (che romperebbe anche la lightbox). */
    @Test
    @Transactional
    void galleryAndListRenderVideosAsVideoElements() throws Exception {
        Generation video = new Generation("pred-gv", "prunaai/p-video", null, "clip", null);
        video.setKind(GenerationKind.VIDEO);
        video.setStatus(GenerationStatus.SUCCEEDED);
        video.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("9-0.mp4")));
        repository.save(video);

        String gallery = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String list = mockMvc.perform(get("/generations")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(gallery).containsPattern("<video[^>]*/images/9-0.mp4");
        assertThat(gallery).doesNotContainPattern("<img[^>]*/images/9-0.mp4");
        assertThat(list).containsPattern("<video[^>]*/images/9-0.mp4");
        assertThat(list).doesNotContainPattern("<img[^>]*/images/9-0.mp4");
    }

    /** Star per file + tab Tutte/Preferiti: la tab Preferiti mostra solo i file con la star, senza checkbox di selezione. */
    @Test
    @Transactional
    void favouritesTabListsOnlyStarredFilesAndToggleEndpointSwapsStar() throws Exception {
        Generation g = new Generation("pred-fav", "owner/model", null, "two files", null);
        g.setStatus(GenerationStatus.SUCCEEDED);
        g.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("7-0.png", "7-1.png")));
        g.setFavouriteFilenames(new java.util.LinkedHashSet<>(java.util.List.of("7-1.png")));
        repository.save(g);

        String all = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String favourites = mockMvc.perform(get("/gallery").param("tab", "favourites")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(all).contains("/images/7-0.png").doesNotContain("/images/7-1.png").contains("/favourite?filename=7-0.png");
        assertThat(favourites).contains("/images/7-1.png").doesNotContain("/images/7-0.png")
                .doesNotContainPattern("<input[^>]*name=\"ids\"").contains("text-favourite");

        mockMvc.perform(post("/generations/" + g.getId() + "/favourite").param("filename", "7-1.png").param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "gallery-update"));
        String empty = mockMvc.perform(get("/gallery").param("tab", "favourites")).andReturn().getResponse().getContentAsString();
        assertThat(empty).doesNotContain("/images/7-1.png");
    }

    /** /errors: pagina intera e frammento htmx, e "Svuota" cancella il registro. */
    @Test
    @Transactional
    void errorsPageListsRecordedErrorsAndClearEmptiesTheLog() throws Exception {
        appErrorRepository.save(new org.dual.replicate.domain.AppError(org.dual.replicate.domain.AppErrorSource.REPLICATE,
                "getPrediction", "ReplicateException", "Replicate non risponde", "stack...", 42L, null, java.time.Instant.now()));

        String page = mockMvc.perform(get("/errors")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fragment = mockMvc.perform(get("/errors").header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Replicate non risponde").contains("getPrediction").contains("/generations/42");
        assertThat(fragment).contains("Replicate non risponde").doesNotContain("<html");

        String cleared = mockMvc.perform(post("/errors/clear").header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cleared).doesNotContain("Replicate non risponde").contains("Nessun errore registrato");
        assertThat(appErrorRepository.count()).isZero();
    }

    /** Ogni pagina porta il contenitore dei toast e il link a /errors nell'header. */
    @Test
    void layoutHasToastContainerAndErrorsNavLink() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("@app-error.window").contains("href=\"/errors\"");
    }

    /**
     * Rifiuto atteso che risale a un controller (qui: la star di un file che non e' della generazione, tab vecchia): NON e'
     * un guasto, quindi nessuna riga nel registro errori, ma l'utente htmx vede comunque il messaggio (toast) e lo status e' 422.
     * Il caso dell'errore vero (registrato, source dell'eccezione, 502) e' in UnhandledExceptionResolverTest.
     */
    @Test
    @Transactional
    void aRejectedRequestGetsAToastButIsNotRecorded() throws Exception {
        Generation g = new Generation("pred-x", "owner/model", null, "p", null);
        g.setStatus(GenerationStatus.SUCCEEDED);
        g.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("8-0.png")));
        repository.save(g);
        long before = appErrorRepository.count();

        var result = mockMvc.perform(post("/generations/" + g.getId() + "/favourite")
                        .param("filename", "nope.png").header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(result.getResponse().getHeader("HX-Trigger")).contains("app-error").contains("\"message\"");
        assertThat(appErrorRepository.count()).isEqualTo(before);
    }

    @Test
    void paramsEndpointReturnsNotFoundForUnknownModel() throws Exception {
        mockMvc.perform(get("/generations/params").param("model", "owner/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    /**
     * Stesso endpoint di sopra, ma per il secondo form-type
     * (FLUX_2_KLEIN_9B, vedi migrazione V7/Flux2Klein9bParameterHandler):
     * verifica che il fragment dedicato renderizzi davvero (non solo che
     * compaia nella select, vedi generationFormRenders) coi campi giusti
     * e il default "go_fast" NON checked (Flux2Klein9bParameterHandler.DEFAULT_GO_FAST
     * e' deliberatamente false, diverso dal default Replicate).
     */
    @Test
    void paramsEndpointRendersFieldsForFlux2Klein9b() throws Exception {
        String body = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-2-klein-9b"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"aspect_ratio\"", "name=\"megapixels\"", "name=\"output_quality\"", "name=\"go_fast\"");
        assertThat(body).containsPattern("<option value=\"1:1\"[^>]*selected");
        assertThat(body).doesNotContainPattern("name=\"go_fast\"[^>]*checked");
    }

    /**
     * Stesso endpoint di sopra, ma per il terzo form-type (FLUX_KREA_DEV,
     * vedi migrazione V10/FluxKreaDevParameterHandler): a differenza del
     * form di FLUX_2_KLEIN_9B sopra, questo espone anche guidance/
     * num_outputs/num_inference_steps (il modello reale li accetta, klein-9b
     * no) e un megapixels a sole 2 opzioni (0.25/1, non 5).
     */
    @Test
    void paramsEndpointRendersFieldsForFluxKreaDev() throws Exception {
        String body = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-krea-dev"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"aspect_ratio\"", "name=\"megapixels\"", "name=\"output_quality\"",
                "name=\"go_fast\"", "name=\"guidance\"", "name=\"num_outputs\"", "name=\"num_inference_steps\"");
        assertThat(body).containsPattern("<option value=\"1:1\"[^>]*selected");
        assertThat(body).doesNotContainPattern("name=\"go_fast\"[^>]*checked");
        assertThat(body).doesNotContain("value=\"0.5\"", "value=\"2\"", "value=\"4\"");
    }

    /**
     * Prompt vuoto/solo spazi: PromptEnhancementService non va chiamato
     * (draft.isEmpty() nel controller lo evita) - l'unico path
     * dell'endpoint di enhance sicuro da esercitare qui a contesto Spring
     * completo, dato che una vera chiamata al PromptEnhancementService
     * reale (bean con ChatClient) contatterebbe davvero OpenRouter. Il
     * path "prompt valido riscritto"/"errore LLM" e' coperto invece da
     * PromptEnhancementServiceTest, con un ChatClient mockato.
     */
    @Test
    void enhancePromptEndpointSkipsLlmCallForBlankPrompt() throws Exception {
        String body = mockMvc.perform(post("/generations/enhance-prompt").param("prompt", "   "))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("id=\"prompt-field\"", "name=\"prompt\"");
    }

    /**
     * Nessuna pagina "senza conversazione": /deep-chat nudo risolve/crea
     * sempre quella di default e ci naviga (vedi DeepChatController).
     * @Transactional: senza, ogni esecuzione di questo test (e degli
     * altri due sotto che toccano CHAT_CONVERSATION/CHAT_MESSAGE)
     * lascerebbe righe permanenti nel DB H2 file-based condiviso con
     * l'ambiente di sviluppo (niente datasource separato per i test in
     * questo progetto) - il rollback automatico a fine test evita
     * l'accumulo silenzioso ad ogni `mvn test`.
     */
    @Test
    @Transactional
    void bareDeepChatRedirectsToAConversation() throws Exception {
        mockMvc.perform(get("/deep-chat"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @Transactional
    void deepChatConversationPageRenders() throws Exception {
        String location = mockMvc.perform(get("/deep-chat"))
                .andReturn().getResponse().getRedirectedUrl();

        mockMvc.perform(get(location)).andExpect(status().isOk());
    }

    /**
     * Copre sia il ramo vuoto (nessuna generazione riuscita in questa
     * conversazione: messaggio i18n) sia quello pieno (grid riusata da
     * fragments/gallery.html, vedi CLAUDE.md/il piano di questa feature)
     * dell'accordion "galleria" di /deep-chat/{id} - in particolare che
     * il th:block che avvolge il th:replace condizionale funzioni
     * davvero (th:if/th:unless sullo STESSO tag di th:replace non
     * basterebbe, th:replace ha precedenza piu' alta e scatterebbe
     * comunque: vedi il commento in deep-chat.html).
     */
    @Test
    @Transactional
    void deepChatContextualGalleryShowsOnlyImagesFromThatConversation() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());

        Generation generation = new Generation("pred-dc-1", "owner/model", null, "a fox", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("dc-1.png"));
        generation = repository.save(generation);

        chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.USER, "genera una volpe", null));
        chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.AI, "ecco la volpe", generation));

        String body = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/images/dc-1.png");
        assertThat(body).doesNotContain("Nessuna immagine ancora in questa conversazione");
        // Overlay "Scarica" sul thumbnail (fragments/button.html :: downloadOverlay).
        assertThat(body).contains("download=\"dc-1.png\"");
        // Il link di dettaglio della card contestuale porta il conversationId (vedi fragments/gallery-card.html), per il link "indietro" del dettaglio (fragments/generation.html :: status, ora su /generations/{id} - vedi CLAUDE.md).
        assertThat(body).contains("/generations/" + generation.getId() + "?conversationId=" + conversation.getId());

        ChatConversation otherConversation = chatConversationRepository.save(new ChatConversation());
        String otherBody = mockMvc.perform(get("/deep-chat/" + otherConversation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(otherBody).doesNotContain("/images/dc-1.png");
        assertThat(otherBody).contains("Nessuna immagine ancora in questa conversazione");
    }

    @Test
    void emptyGalleryRenders() throws Exception {
        mockMvc.perform(get("/gallery")).andExpect(status().isOk());
    }

    @Test
    void emptyGalleryFragmentRenders() throws Exception {
        mockMvc.perform(get("/gallery").header("HX-Request", "true")).andExpect(status().isOk());
    }

    /**
     * Una pagina puo' smettere di esistere fra un refresh e l'altro
     * (cancellazione in blocco dell'ultima pagina, vedi
     * GalleryController#deleteSelected): il refresh SSE (fragments/gallery.html,
     * hx-get="@{/gallery(page=...)}") ri-richiede esattamente la pagina
     * gia' servita, che potrebbe non esistere piu' - GalleryController#list
     * deve ripiegare sull'ultima pagina rimasta, non mostrare "nessuna
     * immagine" mentre pagine precedenti hanno ancora contenuto.
     */
    @Test
    void requestingAPageBeyondTheLastOneFallsBackInsteadOfShowingEmpty() throws Exception {
        Generation generation = new Generation("pred-page-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("page-1.png"));
        repository.save(generation);

        String body = mockMvc.perform(get("/gallery").param("page", "999").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Nessuna immagine ancora");
    }

    @Test
    void generationDetailRendersForSucceededGeneration() throws Exception {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", "{\"seed\":1}");
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("1.png"));
        generation = repository.save(generation);

        mockMvc.perform(get("/gallery")).andExpect(status().isOk());
        mockMvc.perform(get("/gallery").header("HX-Request", "true")).andExpect(status().isOk());
        // Terminale: refresh() ritorna subito, nessuna chiamata a Replicate. Stessa GET del
        // polling, ma a stato terminale e' anche il dettaglio (prompt/parametri/immagini),
        // niente pagina di dettaglio separata - vedi CLAUDE.md.
        mockMvc.perform(get("/generations/" + generation.getId())).andExpect(status().isOk());
    }

    /**
     * Una Generation con num_outputs > 1 deve mostrare TUTTE le immagini nel
     * dettaglio (galleria con lightbox, vedi fragments/generation-images.html),
     * ciascuna con il proprio bottone di cancellazione - non solo la prima.
     * @Transactional: stesso motivo di bareDeepChatRedirectsToAConversation
     * sopra, questa riga (con le sue 3 immagini) non deve restare nel DB di
     * sviluppo condiviso dopo `mvn test`.
     */
    @Test
    @Transactional
    void generationDetailRendersEveryImageOfAMultiOutputGeneration() throws Exception {
        Generation generation = new Generation("pred-multi-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("multi-1.png", "multi-2.png", "multi-3.png"));
        generation = repository.save(generation);

        String body = mockMvc.perform(get("/generations/" + generation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String filename : List.of("multi-1.png", "multi-2.png", "multi-3.png")) {
            assertThat(body).contains("src=\"/images/" + filename + "\"");
            assertThat(body).contains("/generations/" + generation.getId() + "/images/" + filename);
        }
        assertThat(body).contains("generation-image");
    }

    /**
     * Il seed deve essere sempre chiaramente visibile nel dettaglio (vedi
     * Generation.seed/GenerationService#create), non solo sepolto nel
     * blob "Parametri": una riga dedicata, con un placeholder esplicito
     * quando non e' noto (mai una riga che sparisce, a differenza di
     * version/parametri).
     */
    @Test
    void generationDetailShowsSeedRowExplicitlyAndPlaceholderWhenUnknown() throws Exception {
        Generation withSeed = new Generation("pred-seed-1", "owner/model", null, "a cat", "{\"seed\":777}", 777L);
        withSeed.setStatus(GenerationStatus.SUCCEEDED);
        withSeed.setImageFilenames(List.of("seed-1.png"));
        withSeed = repository.save(withSeed);

        String bodyWithSeed = mockMvc.perform(get("/generations/" + withSeed.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bodyWithSeed).contains(">777<");

        Generation withoutSeed = new Generation("pred-seed-2", "owner/model", null, "a dog", null);
        withoutSeed.setStatus(GenerationStatus.SUCCEEDED);
        withoutSeed.setImageFilenames(List.of("seed-2.png"));
        withoutSeed = repository.save(withoutSeed);

        String bodyWithoutSeed = mockMvc.perform(get("/generations/" + withoutSeed.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bodyWithoutSeed).contains("casuale");
    }

    /**
     * Push di seed/prompt dal dettaglio (vedi fragments/generation.html
     * :: status, ramo SUCCEEDED): il prompt punta sempre solo a
     * /generations/new, il seed sia a /generations/new sia a /deep-chat -
     * verso la STESSA conversazione quando conversationId e' presente
     * (arrivati dalla galleria contestuale), altrimenti verso /deep-chat
     * nudo (ultima conversazione attiva, risolta dal redirect di
     * DeepChatController#defaultConversation). Nessun link di push del
     * seed quando il seed e' ignoto (nulla da riusare).
     */
    @Test
    void generationDetailPushLinksTargetGenerationsAndDeepChat() throws Exception {
        Generation withSeed = new Generation("pred-push-1", "owner/model", null, "a cat", "{\"seed\":777}", 777L);
        withSeed.setStatus(GenerationStatus.SUCCEEDED);
        withSeed.setImageFilenames(List.of("push-1.png"));
        withSeed = repository.save(withSeed);

        String fromGallery = mockMvc.perform(get("/generations/" + withSeed.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fromGallery).contains("/generations/new?prompt=");
        assertThat(fromGallery).contains("/generations/new?seed=777");
        assertThat(fromGallery).contains("href=\"/deep-chat?seed=777\"");

        String fromConversation = mockMvc.perform(get("/generations/" + withSeed.getId()).param("conversationId", "7"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fromConversation).contains("href=\"/deep-chat/7?seed=777\"");

        Generation withoutSeed = new Generation("pred-push-2", "owner/model", null, "a dog", null);
        withoutSeed.setStatus(GenerationStatus.SUCCEEDED);
        withoutSeed.setImageFilenames(List.of("push-2.png"));
        withoutSeed = repository.save(withoutSeed);

        String withoutSeedBody = mockMvc.perform(get("/generations/" + withoutSeed.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutSeedBody).contains("/generations/new?prompt=");
        assertThat(withoutSeedBody).doesNotContain("/generations/new?seed=").doesNotContain("/deep-chat?seed=");
    }

    /** Push del seed dal dettaglio (vedi sopra): /generations/new lo pre-compila nel campo del form-type corrente. */
    @Test
    void generationFormPrefillsSeedFromQueryParam() throws Exception {
        String body = mockMvc.perform(get("/generations/new").param("seed", "777"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern("id=\"param-seed\"[^>]*value=\"777\"");
    }

    /** Push del seed dal dettaglio verso /deep-chat nudo (nessuna conversazione di contesto): il redirect deve propagarlo. */
    @Test
    @Transactional
    void deepChatRedirectPreservesSeedQueryParam() throws Exception {
        String location = mockMvc.perform(get("/deep-chat").param("seed", "777"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).endsWith("?seed=777");
    }

    /** Push del seed dal dettaglio verso una conversazione specifica: il pannello impostazioni lo pre-compila. */
    @Test
    @Transactional
    void deepChatConversationPagePrefillsSeedFromQueryParam() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());

        String body = mockMvc.perform(get("/deep-chat/" + conversation.getId()).param("seed", "777"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern("id=\"param-seed\"[^>]*value=\"777\"");
    }

    /**
     * Il link "indietro" del dettaglio dipende da dove si arriva (vedi
     * GenerationController#status): dalla galleria globale o da una
     * generazione appena creata (nessun param) torna a /gallery, dal
     * listato /generations (generationsPage) torna a quella pagina,
     * dalla galleria contestuale di una conversazione /deep-chat
     * (conversationId sulla query string, propagato da
     * fragments/gallery-card.html) torna a quella conversazione.
     */
    @Test
    void generationDetailBackLinkDependsOnOrigin() throws Exception {
        Generation generation = new Generation("pred-2", "owner/model", null, "a dog", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("2.png"));
        generation = repository.save(generation);

        String fromGallery = mockMvc.perform(get("/generations/" + generation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fromGallery).contains("href=\"/gallery\"").doesNotContain("/deep-chat/");

        String fromDeepChat = mockMvc.perform(get("/generations/" + generation.getId()).param("conversationId", "7"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fromDeepChat).contains("href=\"/deep-chat/7\"");

        String fromGenerationsList = mockMvc.perform(get("/generations/" + generation.getId()).param("generationsPage", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fromGenerationsList).contains("href=\"/generations?page=2\"");
    }

    /**
     * Cancellazione SINGOLA dalla pagina di dettaglio (vedi
     * fragments/generation.html :: status, ramo SUCCEEDED/FAILED): dopo
     * la cancellazione la pagina corrente non esiste piu', quindi il
     * controller porta il browser a /gallery (default, nessuna
     * provenienza specifica) via l'header HX-Redirect (non uno swap di
     * contenuto) — diverso dalla cancellazione in blocco dalla griglia
     * (deleteSelectedRemovesEveryGeneration sotto), dove la pagina
     * corrente resta valida.
     */
    @Test
    void deleteSetsHxRedirectHeader() throws Exception {
        Generation generation = new Generation("pred-3", "owner/model", null, "a bird", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("3.png"));
        generation = repository.save(generation);

        mockMvc.perform(delete("/generations/" + generation.getId()))
                .andExpect(header().string("HX-Redirect", "/gallery"));
    }

    /**
     * Regressione: una Generation nata da /deep-chat resta referenziata da
     * un CHAT_MESSAGE (FK_CHAT_MESSAGE_GENERATION) finche' la conversazione
     * non viene a sua volta cancellata - prima della migrazione V9 quella
     * FK non aveva un ON DELETE, quindi cancellarla da qui falliva con una
     * violazione del vincolo DOPO che il file immagine era gia' stato
     * rimosso da storage (IImageStorageService#delete, chiamato prima della
     * riga DB in GenerationService#delete): risultato, un'immagine sparita
     * dal disco ma ancora elencata in galleria con tutti i suoi dettagli.
     * Niente @Transactional qui (a differenza di altri test in questa
     * classe, ma come i suoi vicini deleteSetsHxRedirectHeader/
     * deleteSelectedRemovesEveryGeneration sopra): la DELETE deve essere
     * davvero committata perche' scatti l'ON DELETE SET NULL della
     * migrazione V9 - dentro un'unica transazione di test, ancora aperta
     * al momento della query sotto, l'istruzione DELETE resterebbe solo
     * "in coda" nel persistence context di Hibernate, senza innescare il
     * trigger del database.
     */
    @Test
    void deleteRemovesGenerationReferencedByAChatMessage() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());

        Generation generation = new Generation("pred-chat-del-1", "owner/model", null, "a fox", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("chat-del-1.png"));
        generation = repository.save(generation);

        ChatMessage message = chatMessageRepository.save(
                new ChatMessage(conversation, ChatMessageRole.AI, "ecco la volpe", generation));

        mockMvc.perform(delete("/generations/" + generation.getId()))
                .andExpect(header().string("HX-Redirect", "/gallery"));

        assertThat(repository.findById(generation.getId())).isEmpty();
        assertThat(chatMessageRepository.findById(message.getId()))
                .isPresent()
                .get()
                .extracting(ChatMessage::getGeneration)
                .isNull();
    }

    /**
     * Regressione: una tab che sta ancora pollando GET /generations/{id}
     * ogni 2s (vedi fragments/generation.html) non deve incappare in un
     * 500 quando la generazione sparisce nel frattempo (cancellata da
     * un'altra tab/dal listato - vedi GenerationController#status).
     * Simula la race cancellando direttamente la riga via repository
     * (niente chiamata a /generations/{id} DELETE, che scaricherebbe
     * anche il file immagine: qui basta che la riga non esista piu' al
     * momento del refresh) invece di aspettare una vera race
     * concorrente. Copre entrambi i rami di status(): richiesta htmx
     * (header HX-Redirect) e navigazione diretta del browser (redirect
     * HTTP), con lo stesso identico target "indietro" a parita' di
     * conversationId/generationsPage (vedi backPath/backTarget).
     */
    @Test
    void statusRedirectsInsteadOfErroringWhenGenerationWasDeletedConcurrently() throws Exception {
        Generation htmxGeneration = new Generation("pred-race-htmx", "owner/model", null, "a wolf", null);
        htmxGeneration.setStatus(GenerationStatus.PROCESSING);
        htmxGeneration = repository.save(htmxGeneration);
        repository.deleteById(htmxGeneration.getId());

        mockMvc.perform(get("/generations/" + htmxGeneration.getId())
                        .param("generationsPage", "3")
                        .header("HX-Request", "true"))
                .andExpect(header().string("HX-Redirect", "/generations?page=3"));

        Generation browserGeneration = new Generation("pred-race-browser", "owner/model", null, "a wolf", null);
        browserGeneration.setStatus(GenerationStatus.PROCESSING);
        browserGeneration = repository.save(browserGeneration);
        repository.deleteById(browserGeneration.getId());

        mockMvc.perform(get("/generations/" + browserGeneration.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/gallery"));
    }

    /**
     * Ramo in corso di fragments/generation.html :: status: placeholder con bottone
     * "Interrompi" (hx-post verso /generations/{id}/cancel); cancelDisabled=true lo
     * disabilita e viene propagato nell'hx-get del polling. Renderizza il fragment
     * direttamente col template engine (NON via GET /generations/{id}: refresh()
     * chiamerebbe la vera API Replicate).
     */
    @Test
    void inProgressStatusRendersPlaceholderWithCancelButton() {
        Generation generation = new Generation("pred-ph-1", "owner/model", null, "a fox", null);
        generation.setStatus(GenerationStatus.PROCESSING);
        org.springframework.test.util.ReflectionTestUtils.setField(generation, "id", 42L);

        String enabled = renderStatus(generation, false);
        assertThat(enabled).contains("data-generation-placeholder", "hx-post=\"/generations/42/cancel", "hx-confirm=");
        assertThat(enabled).doesNotContainPattern("<button[^>]*\\sdisabled[\\s=>]");

        String disabled = renderStatus(generation, true);
        assertThat(disabled).containsPattern("<button[^>]*\\sdisabled[\\s=>]").contains("cancelDisabled=true");
    }

    /**
     * /deep-chat ospita il template del risultato (rimpiazza il placeholder): bottone
     * nascondi/mostra con le due etichette i18n e contenitore delle immagini.
     */
    @Test
    void deepChatPageEmbedsResultTemplateWithToggle() throws Exception {
        String location = mockMvc.perform(get("/deep-chat"))
                .andReturn().getResponse().getHeader("Location");
        String body = mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("generation-result-tpl", "data-generation-result", "gen-toggle",
                "class=\"gen-images\"", "data-hide-text=", "data-show-text=");
    }

    private String renderStatus(Generation generation, boolean cancelDisabled) {
        // WebContext (non Context): i link @{/...} lo richiedono per risolversi rispetto al context path.
        var servletContext = new org.springframework.mock.web.MockServletContext();
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new org.springframework.mock.web.MockHttpServletRequest(servletContext),
                        new org.springframework.mock.web.MockHttpServletResponse());
        var context = new org.thymeleaf.context.WebContext(exchange, java.util.Locale.ITALIAN);
        context.setVariable("generation", generation);
        context.setVariable("conversationId", null);
        context.setVariable("generationsPage", null);
        context.setVariable("cancelDisabled", cancelDisabled ? Boolean.TRUE : null);
        return templateEngine.process("fragments/generation", java.util.Set.of("status"), context);
    }

    /**
     * Griglia (globale o contestuale, stesso fragment fragments/gallery.html
     * :: grid): checkbox di selezione + bottone "Elimina selezionate"
     * disabilitato di default (nessuna selezione al primo caricamento,
     * vedi fragments/button.html :: dangerSelectable) devono comparire
     * nel markup renderizzato.
     */
    @Test
    void galleryGridRendersSelectionCheckboxAndDisabledDeleteButton() throws Exception {
        Generation generation = new Generation("pred-sel-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("sel-1.png"));
        generation = repository.save(generation);

        String body = mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"ids\"", "x-model=\"selectedIds\"", ":disabled=\"selectedIds.length === 0\"",
                "hx-post=\"/gallery/delete-selected\"");
        // Attributo "disabled" LETTERALE (non solo il binding Alpine ":disabled"): senza, il bottone sarebbe
        // cliccabile per un istante al primo paint, prima che Alpine inizializzi - vedi fragments/button.html.
        assertThat(body).containsPattern("<button[^>]*\\bdisabled\\b[^>]*hx-post=\"/gallery/delete-selected\"[^>]*>");
    }

    /**
     * L'endpoint di cancellazione in blocco cancella davvero righe e file
     * (vedi GenerationService#deleteAll), a differenza del bottone lato
     * client (disabilitato quando la selezione e' vuota, mai testabile
     * qui: MockMvc non esegue JS/Alpine).
     */
    @Test
    void deleteSelectedRemovesEveryGeneration() throws Exception {
        Generation first = new Generation("pred-bulk-1", "owner/model", null, "a cat", null);
        first.setStatus(GenerationStatus.SUCCEEDED);
        first.setImageFilenames(List.of("bulk-1.png"));
        first = repository.save(first);

        Generation second = new Generation("pred-bulk-2", "owner/model", null, "a dog", null);
        second.setStatus(GenerationStatus.SUCCEEDED);
        second.setImageFilenames(List.of("bulk-2.png"));
        second = repository.save(second);

        mockMvc.perform(post("/gallery/delete-selected")
                        .param("ids", first.getId().toString(), second.getId().toString()))
                .andExpect(status().isOk());

        assertThat(repository.findById(first.getId())).isEmpty();
        assertThat(repository.findById(second.getId())).isEmpty();
    }

    /**
     * La galleria contestuale di /deep-chat ascolta anche l'evento SSE
     * generico "gallery-update" (non solo "new-message"): una
     * cancellazione dalla griglia globale (o da un'altra conversazione)
     * deve riflettersi anche qui, vedi GenerationService#delete/#deleteAll
     * e GenerationEventBroadcaster#onGenerationsDeleted.
     */
    @Test
    @Transactional
    void deepChatContextualGalleryListensToGalleryUpdateEvent() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());

        String body = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("hx-trigger=\"new-message from:#deep-chat-el, gallery-update from:body\"");
    }

    @Test
    void generationStatusRendersForFailedGeneration() throws Exception {
        Generation generation = new Generation("pred-2", "owner/model", null, "a dog", null);
        generation.setStatus(GenerationStatus.FAILED);
        generation.setErrorMessage("Generazione fallita su Replicate.");
        generation = repository.save(generation);

        mockMvc.perform(get("/generations/" + generation.getId())).andExpect(status().isOk());
    }

    /**
     * Listato /generations: a differenza di /gallery, qualunque stato
     * (qui una PENDING, mai esposta dalla galleria) deve comparire.
     * Copre sia pagina intera sia fragment (HX-Request), stesso motivo
     * di ogni altro test "renders" in questa classe.
     */
    @Test
    void generationsListRendersFullPageAndFragmentForAnyStatus() throws Exception {
        Generation pending = new Generation("pred-list-1", "owner/model", null, "a cat", null);
        repository.save(pending);

        mockMvc.perform(get("/generations")).andExpect(status().isOk());
        String fragmentBody = mockMvc.perform(get("/generations").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fragmentBody).contains("a cat");
    }

    /**
     * Stesso aggiustamento "pagina svuotata da una cancellazione" gia'
     * verificato per /gallery (requestingAPageBeyondTheLastOneFallsBackInsteadOfShowingEmpty):
     * GenerationController#list deve ripiegare sull'ultima pagina
     * rimasta, non mostrare "nessuna generazione" mentre pagine
     * precedenti hanno ancora contenuto.
     */
    @Test
    void generationsListFallsBackWhenPageBeyondLast() throws Exception {
        repository.save(new Generation("pred-list-2", "owner/model", null, "a cat", null));

        String body = mockMvc.perform(get("/generations").param("page", "999").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Nessuna generazione ancora");
    }

    /**
     * Cancellazione della sola selezione (checkbox multiple, vedi
     * fragments/generations.html :: list): stesso principio di
     * deleteSelectedRemovesEveryGeneration per /gallery, ma qui una
     * terza generazione NON selezionata deve sopravvivere.
     */
    @Test
    void generationsDeleteSelectedRemovesOnlySelected() throws Exception {
        Generation first = repository.save(new Generation("pred-gsel-1", "owner/model", null, "a cat", null));
        Generation second = repository.save(new Generation("pred-gsel-2", "owner/model", null, "a dog", null));
        Generation untouched = repository.save(new Generation("pred-gsel-3", "owner/model", null, "a fox", null));

        mockMvc.perform(post("/generations/delete-selected")
                        .param("ids", first.getId().toString(), second.getId().toString()))
                .andExpect(status().isOk());

        assertThat(repository.findById(first.getId())).isEmpty();
        assertThat(repository.findById(second.getId())).isEmpty();
        assertThat(repository.findById(untouched.getId())).isPresent();
    }

    /**
     * Cancellazione di riga singola dal listato (bottone "Elimina" di
     * fragments/generation-row.html): a differenza di delete-selected,
     * ignora qualunque id passato come parametro form-wide - qui non ne
     * passiamo nessuno, la sola presenza dell'id nel path deve bastare.
     */
    @Test
    void generationsDeleteOneRemovesSingleRow() throws Exception {
        Generation generation = repository.save(new Generation("pred-gone-1", "owner/model", null, "a cat", null));

        mockMvc.perform(post("/generations/" + generation.getId() + "/delete"))
                .andExpect(status().isOk());

        assertThat(repository.findById(generation.getId())).isEmpty();
    }

    // NOTA: nessun test MockMvc per POST /generations/delete-all in questa classe.
    // Questa classe gira contro il vero DB/storage di sviluppo (nessun datasource
    // separato per i test, vedi javadoc in cima al file) - un test che chiama
    // repository.findAll() su OGNI riga esistente e le cancella tutte sarebbe
    // distruttivo per qualunque dato reale presente in locale, a differenza di
    // ogni altro test qui che tocca solo le righe che crea da solo. La logica di
    // deleteEverything() e' gia' coperta senza questo rischio da
    // GenerationServiceTest#deleteEverything* (repository mockato, nessun disco/DB reale).

    /**
     * Cancellazione per-immagine, caso NON a cascata (restano altre
     * immagini): niente HX-Redirect, la risposta e' il fragment della
     * griglia aggiornata (vedi GenerationController#deleteImage).
     */
    @Test
    void deleteImageOfMultiImageGenerationReRendersGridWithoutRedirect() throws Exception {
        Generation generation = new Generation("pred-img-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("img-1-0.png", "img-1-1.png"));
        generation = repository.save(generation);

        String body = mockMvc.perform(delete("/generations/" + generation.getId() + "/images/img-1-0.png"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("HX-Redirect"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("img-1-0.png").contains("img-1-1.png");
        assertThat(repository.findById(generation.getId()))
                .isPresent().get()
                .extracting(Generation::getImageFilenames).isEqualTo(List.of("img-1-1.png"));
    }

    /**
     * Cancellazione per-immagine, caso A CASCATA (era l'ultima
     * immagine): l'intera generazione sparisce, stesso HX-Redirect di
     * una cancellazione whole-generation (vedi deleteSetsHxRedirectHeader).
     */
    @Test
    void deleteImageOfLastImageCascadesAndRedirects() throws Exception {
        Generation generation = new Generation("pred-img-2", "owner/model", null, "a dog", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("img-2-0.png"));
        generation = repository.save(generation);

        mockMvc.perform(delete("/generations/" + generation.getId() + "/images/img-2-0.png"))
                .andExpect(header().string("HX-Redirect", "/gallery"));

        assertThat(repository.findById(generation.getId())).isEmpty();
    }

    /**
     * Prova end-to-end del meccanismo i18n (vedi CLAUDE.md, sezione
     * dedicata): "it"/"en" devono risolvere il bundle giusto (lang
     * dell'&lt;html&gt; + una stringa nota tradotta), "de" (non mappata)
     * deve ricadere sul bundle italiano di default, non sul default
     * della JVM. "??" e' il marcatore che Thymeleaf usa per una chiave
     * di messaggio non risolta: la sua assenza copre ogni chiave usata
     * dalla pagina in un colpo solo, senza elencarle una per una. Copre
     * anche il comportamento di merge di thymeleaf-layout-dialect
     * sull'attributo lang (th:lang dinamico sul decoratore, nessun
     * lang letterale sulle pagine, altrimenti vincerebbe sempre quello
     * letterale - vedi fragments/layout.html).
     */
    @ParameterizedTest
    @CsvSource({
            "it,     it, Galleria",
            "en,     en, Gallery",
            "de-DE,  it, Galleria"
    })
    void localeSwitchesUiTextAndHtmlLang(String acceptLanguage, String expectedLang, String expectedNavText) throws Exception {
        String body = mockMvc.perform(get("/").header("Accept-Language", acceptLanguage))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("??");
        assertThat(body).contains("<html lang=\"" + expectedLang + "\"");
        assertThat(body).contains(expectedNavText);
    }

    /**
     * Cattura una chiave aggiunta a un bundle e dimenticata nell'altro:
     * i due file devono avere esattamente lo stesso set di chiavi,
     * indipendentemente da quali pagine i test sopra esercitano
     * davvero.
     */
    @Test
    void messageBundlesHaveMatchingKeys() throws IOException {
        Properties it = loadProperties("/messages.properties");
        Properties en = loadProperties("/messages_en.properties");

        assertThat(it.keySet()).containsExactlyInAnyOrderElementsOf(en.keySet());
    }

    private static Properties loadProperties(String classpathResource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = TemplateRenderingTests.class.getResourceAsStream(classpathResource)) {
            properties.load(in);
        }
        return properties;
    }

    /** Da zero (?kind=video) c'e' il campo di upload; con una sorgente "Anima" no. */
    @Test
    @Transactional
    void uploadFieldOnlyWhenStartingVideoFromScratch() throws Exception {
        String body = mockMvc.perform(get("/generations/new").param("kind", "video"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"sourceUpload\"");
    }

    /** Modifica: pagina dedicata, solo il modello di modifica, upload obbligatorio; le altre pagine non lo elencano. */
    @Test
    @Transactional
    void editPageListsOnlyTheEditModelWithARequiredUpload() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String edit = mockMvc.perform(get("/generations/new").param("kind", "edit"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String images = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String chat = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(edit).contains("black-forest-labs/flux-kontext-dev").doesNotContain("black-forest-labs/flux-krea-dev")
                .doesNotContain("prunaai/p-video");
        // Il tag contiene un '>' dentro @change (f.size > ...): si cerca fino al '<' successivo, non al '>'.
        assertThat(edit).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
        assertThat(edit).contains("name=\"aspect_ratio\"").contains("match_input_image");
        assertThat(images).doesNotContain("black-forest-labs/flux-kontext-dev");
        assertThat(chat).doesNotContain("black-forest-labs/flux-kontext-dev");
        // Link nell'header su ogni pagina.
        assertThat(images).contains("/generations/new?kind=edit");
    }

    /** "Modifica" da una generazione: preseleziona il modello di modifica (non p-video), porta la sorgente, niente upload. */
    @Test
    @Transactional
    void editFormWithSourceCarriesTheSourceAndPreselectsTheEditModel() throws Exception {
        Generation image = new Generation("pred-edit-src", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("1-0.png")));
        image = repository.save(image);

        String body = mockMvc.perform(get("/generations/new").param("kind", "edit")
                        .param("source", String.valueOf(image.getId())).param("sourceImage", "1-0.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("name=\"sourceUpload\"");
        assertThat(body).containsPattern("name=\"sourceGenerationId\"[^>]*value=\"" + image.getId() + "\"");
        assertThat(body).containsPattern("<option value=\"black-forest-labs/flux-kontext-dev\"[^>]*selected");
        assertThat(body).doesNotContain("prunaai/p-video");
    }

    /** Ogni thumbnail immagine offre "Modifica" verso la pagina di modifica. */
    @Test
    @Transactional
    void galleryCardOffersEditForImages() throws Exception {
        Generation image = new Generation("pred-edit-card", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("2-0.png")));
        image = repository.save(image);

        String body = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("kind=edit").contains("source=" + image.getId());
    }
}
