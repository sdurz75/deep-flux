package org.dual.hexa.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.dual.hexa.ai.chat.adapter.in.web.DeepChatController;
import org.dual.hexa.app.generation.adapter.in.web.GalleryController;
import org.dual.hexa.app.generation.adapter.in.web.GenerationController;
import org.dual.hexa.app.shared.domain.AppEventSource;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.ai.chat.domain.ChatConversation;
import org.dual.hexa.ai.chat.domain.ChatMessage;
import org.dual.hexa.ai.chat.domain.ChatMessageRole;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.GenerationStatus;
import org.dual.hexa.ai.chat.port.out.IChatConversationStore;
import org.dual.hexa.ai.chat.port.out.IChatMessageStore;
import org.dual.hexa.app.generation.port.out.IGenerationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    /** Salvare un LoRA anagrafato con sorgente owner/nome interroga Replicate (sola lettura): nei test mai la rete vera. */
    @MockitoBean
    private org.dual.hexa.app.generation.port.out.IPredictionGateway predictionGateway;

    @Autowired
    private org.thymeleaf.spring6.SpringTemplateEngine templateEngine;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.dual.hexa.core.events.port.out.ISystemEventStore systemEventRepository;

    @Autowired
    private IGenerationStore repository;

    @Autowired
    private IChatConversationStore chatConversationRepository;

    @Autowired
    private IChatMessageStore chatMessageRepository;

    @Test
    void generationFormRenders() throws Exception {
        mockMvc.perform(get("/generations/new")).andExpect(status().isOk());
    }

    /** La barra di stato (fragments/core/status-bar.html) e' nel layout di ogni pagina intera: da classi sciolte l'ora di build c'e' sempre. */
    @Test
    void buildInfoIsRenderedInTheBottomBar() throws Exception {
        String page = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).containsPattern("id=\"build-info\"[^>]*>\\s*<span>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}</span>");
    }

    /**
     * A destra della barra: lo slot dei crediti dell'app (caricato via htmx, nessuna chiamata remota nel render della pagina) e il selettore
     * del tema, che non sta piu' nel menu Gestione.
     */
    @Test
    void bottomBarCarriesTheCreditsSlotAndTheThemeSwitchOnly() throws Exception {
        String page = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("hx-get=\"/credits/bar\"", "id=\"status-credits\"");
        assertThat(countOccurrences(page, "data-theme-value=")).isEqualTo(3);
        assertThat(page.indexOf("data-theme-value=")).isGreaterThan(page.indexOf("id=\"status-credits\""));
    }

    /**
     * fragments/core/live-events.html non ha nomi di eventi hardcoded: li legge da app.push.client-events / reconnect-events
     * (core.push.PushModelAdvice), e "system-event" (toast) e' sempre gestito.
     */
    @Test
    void liveEventsBridgeTakesTheAppEventNamesFromConfiguration() throws Exception {
        String page = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("new EventSource(eventsUrl)")
                .containsPattern("appEvents = \\[\\s*\"gallery-update\",\\s*\"chat-message\",\\s*\"training-update\"\\s*\\]")
                .containsPattern("reconnectEvents = \\[\\s*\"gallery-update\",\\s*\"training-update\"\\s*\\]")
                .contains("addEventListener('system-event'");
    }

    /**
     * Regressione: th:case e th:replace sullo STESSO tag in
     * fragments/app/generation-params.html non filtravano nulla (l'ordine di
     * precedenza degli attributi di Thymeleaf processa th:replace PRIMA
     * di th:switch/th:case), quindi un caricamento pieno di /generations/new
     * (o /deep-chat/{id}, stesso fragment condiviso) concatenava TUTTI E
     * TRE i form-type invece del solo formType corrente - bug presente su
     * ogni singola richiesta, non solo dopo una navigazione (osservato
     * dal vivo con un curl fresco, non solo dopo "esco e torno" come
     * inizialmente segnalato: quella frase descriveva solo QUANDO l'utente
     * se ne era accorto, non la vera condizione di innesco). Il fix
     * annida th:case/th:replace su due <th:block> distinti (vedi il
     * fragment): qui si verifica che, col modello di default (un fine-tune LoRA,
     * SORT_ORDER=0), compaiano SOLO i suoi campi (flux_model/lora_scale),
     * mai quelli di klein-9b/krea-dev (go_fast, megapixels a 5 opzioni), e che
     * "param-seed" (nome ripetuto identico nei tre fragment) appaia
     * esattamente una volta.
     */
    @Test
    void generationFormRendersOnlyTheDefaultModelFieldsOnce() throws Exception {
        String body = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"flux_model\"");
        // il form-type dei fine-tune LoRA ha il proprio megapixels (0.25/1, con un'immagine di partenza): una sola select e nessuna opzione esclusiva di klein-9b.
        assertThat(body).doesNotContain("name=\"go_fast\"", "value=\"0.5\"", "value=\"2\"", "value=\"4\"");
        assertThat(countOccurrences(body, "id=\"param-megapixels\"")).isEqualTo(1);
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

    /** Un create rifiutato ri-renderizza la form col seed sottomesso (non e' fra i defaultFields): il salvataggio lato client non lo perde. */
    @Test
    void rejectedCreateKeepsTheSubmittedSeed() throws Exception {
        String body = mockMvc.perform(post("/generations")
                        .param("model", "owner/does-not-exist")
                        .param("prompt", "a cat")
                        .param("seed", "4242"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern("name=\"seed\"[^>]*value=\"4242\"");
    }

    /**
     * Stato della form di /generations/new persistito lato client (fragments/app/generation-settings-persist.html): chiave per
     * tipo di pagina, separata da quella della chat; `version` mai scritta; prompt da link esplicito non ripristinato;
     * bottone di reset con il primo modello del tipo di pagina come default.
     */
    @Test
    void newFormCarriesClientSidePersistenceConfig() throws Exception {
        String image = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String video = mockMvc.perform(get("/generations/new").param("kind", "video"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Vecchi link della pagina di modifica (kind=edit): ora e' la pagina delle immagini.
        String oldEditLink = mockMvc.perform(get("/generations/new").param("kind", "edit"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reused = mockMvc.perform(get("/generations/new").param("prompt", "a cat"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(image).contains("data-persist-key=\"generate.image\"").contains("data-persist-ignore=\"version\"")
                .doesNotContainPattern("data-persist-no-restore=\"[^\"]*(prompt|seed)");
        assertThat(video).contains("data-persist-key=\"generate.video\"");
        assertThat(oldEditLink).contains("data-persist-key=\"generate.image\"").doesNotContain("generate.edit");
        assertThat(reused).contains("data-persist-no-restore=\"prompt\"");
        // Slot globale prompt/seed: entrambi, su ogni pagina.
        assertThat(image).contains("data-shared-accept=\"prompt seed\"");
        assertThat(video).contains("data-shared-accept=\"prompt seed\"");
        // Script condiviso incluso una volta, bottone di reset che punta ai default del tipo di pagina.
        assertThat(image).contains("form[data-persist-key]").contains("generation-settings:sync");
        assertThat(video).containsPattern("data-default-model=\"prunaai/p-video\"")
                .contains("/generations/params?model=prunaai/p-video");
    }

    /** La chat usa lo stesso script condiviso con la sua chiave storica (nessuna perdita delle preferenze salvate). */
    @Test
    @Transactional
    void deepChatUsesTheSharedPersistenceScriptWithItsHistoricKey() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String chat = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(chat).contains("data-persist-key=\"deepChat.generationSettings\"")
                .contains("form[data-persist-key]").contains("window.setDeepChatSettings(event.detail)");
        assertThat(chat.indexOf("window.setDeepChatSettings(event.detail)")).isLessThan(chat.indexOf("form[data-persist-key]"));
    }

    /**
     * Le azioni proposte dall'assistente (annulla/cancella/rigenera) hanno un template ciascuna: URL dell'app via @{...} (context path),
     * conferma sulle sole azioni distruttive, nessun hx-* (vive nello shadow DOM di deep-chat) e classe chat-action pilotata dal client.
     */
    @Test
    @Transactional
    void deepChatRendersTheProposedActionButtons() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String chat = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String cancel = templateOf(chat, "chat-action-cancel-tpl");
        assertThat(cancel).contains("class=\"chat-action\"", "data-action-type=\"CANCEL\"", "data-url=\"/generations/GENID/cancel\"",
                "data-confirm=\"").doesNotContain("hx-post");
        String delete = templateOf(chat, "chat-action-delete-tpl");
        // Messaggi SENZA parametri: l'apostrofo resta letterale, raddoppiarlo (''), come nei messaggi con parametri, lo mostrerebbe due volte.
        assertThat(cancel + delete).doesNotContain("&#39;&#39;");
        assertThat(delete).contains("data-action-type=\"DELETE\"", "data-url=\"/generations/GENID/delete\"", "data-confirm=\"");
        String regenerate = templateOf(chat, "chat-action-regenerate-tpl");
        assertThat(regenerate).contains("data-action-type=\"REGENERATE\"", "data-url=\"/generations/new\"", "ACTMODEL")
                .doesNotContain("data-confirm");
        assertThat(chat).contains("'chat-action': {");
        // Animazione e sorgente aprono una form (nessuna conferma); l'eliminazione di un file passa dall'endpoint della galleria contestuale.
        String animate = templateOf(chat, "chat-action-animate-tpl");
        assertThat(animate).contains("data-action-type=\"ANIMATE\"", "data-url=\"/generations/new\"", "GENID", "ACTFILE").doesNotContain("data-confirm");
        String useAsSource = templateOf(chat, "chat-action-use-as-source-tpl");
        assertThat(useAsSource).contains("data-action-type=\"USE_AS_SOURCE\"", "data-url=\"/generations/new?kind=image\"", "ACTFILE").doesNotContain("data-confirm");
        String deleteFile = templateOf(chat, "chat-action-delete-file-tpl");
        assertThat(deleteFile).contains("data-action-type=\"DELETE_FILE\"", "data-url=\"/gallery/delete-selected-files\"", "data-confirm=\"", "ACTFILE");
        // Il client manda solo l'ultimo messaggio: la cronologia per il modello la costruisce il server.
        assertThat(chat).contains("requestBodyLimits='{\"maxMessages\": 1}'");
    }

    private static String templateOf(String page, String id) {
        int start = page.indexOf("<template id=\"" + id + "\">");
        assertThat(start).as(id).isGreaterThanOrEqualTo(0);
        return page.substring(start, page.indexOf("</template>", start));
    }

    /**
     * Endpoint htmx-only scatenato dalla select modello ad ogni cambio
     * (vedi fragments/app/generation-params.html): deve ritornare i campi
     * del form-type di quel modello, non una pagina intera.
     */
    @Test
    void paramsEndpointRendersFieldsForKnownModel() throws Exception {
        mockMvc.perform(get("/generations/params").param("model", "sdurz75/flux-lora-ff3"))
                .andExpect(status().isOk());
    }

    /**
     * flux_model marca l'<option> selezionata lato server (non solo via Alpine): il ripristino dello stato salvato scrive la
     * select prima che Alpine parta, e Alpine legge il valore dal DOM (altrimenti riscriverebbe "dev" su un "schnell" ripristinato).
     */
    @Test
    void fluxModelOptionIsMarkedSelectedByTheServer() throws Exception {
        String schnell = mockMvc.perform(get("/generations/params").param("model", "sdurz75/flux-lora-ff3").param("flux_model", "schnell"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String byDefault = mockMvc.perform(get("/generations/params").param("model", "sdurz75/flux-lora-ff3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(schnell).containsPattern("<option value=\"schnell\"[^>]*selected").doesNotContainPattern("<option value=\"dev\"[^>]*selected");
        assertThat(byDefault).containsPattern("<option value=\"dev\"[^>]*selected").doesNotContainPattern("<option value=\"schnell\"[^>]*selected");
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

    /** Tag utente: editor per generazione e per file (si sostituisce da solo), chip in galleria e filtro /gallery?tag= su ogni tab. */
    @Test
    @Transactional
    void tagEndpointsSwapTheEditorAndTheGalleryFiltersByGenerationOrFileTag() throws Exception {
        Generation g = new Generation("pred-tag", "owner/model", null, "taggata", null);
        g.setStatus(GenerationStatus.SUCCEEDED);
        g.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("8-0.png", "8-1.png")));
        g.setFavouriteFilenames(new java.util.LinkedHashSet<>(java.util.List.of("8-1.png")));
        g = repository.save(g);
        Generation other = new Generation("pred-other", "owner/model", null, "altra", null);
        other.setStatus(GenerationStatus.SUCCEEDED);
        other.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("9-0.png")));
        repository.save(other);

        String generationEditor = mockMvc.perform(post("/generations/" + g.getId() + "/tags/add").param("tag", " Estate "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fileEditor = mockMvc.perform(post("/generations/" + g.getId() + "/tags/add").param("tag", "Rosso").param("filename", "8-1.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(generationEditor).contains("id=\"gen-tags-" + g.getId() + "\"").contains("<span>estate</span>").contains("id=\"known-tags\"")
                .contains("/generations/" + g.getId() + "/tags/remove").contains("name=\"tag\"");
        assertThat(fileEditor).contains("id=\"file-tags-" + g.getId() + "-8-1-png\"").contains("<span>rosso</span>").doesNotContain("estate")
                .contains("name=\"filename\" value=\"8-1.png\"").doesNotContain("id=\"known-tags\"");

        // filtro: tag della generazione (tab Tutte), tag del solo file (tab Tutte e Preferiti), nessun risultato per un tag sconosciuto
        String byGeneration = mockMvc.perform(get("/gallery").param("tag", "ESTATE")).andReturn().getResponse().getContentAsString();
        String byFile = mockMvc.perform(get("/gallery").param("tag", "rosso")).andReturn().getResponse().getContentAsString();
        String favouritesByFile = mockMvc.perform(get("/gallery").param("tab", "favourites").param("tag", "rosso")).andReturn().getResponse().getContentAsString();
        String unknown = mockMvc.perform(get("/gallery").param("tag", "inesistente")).andReturn().getResponse().getContentAsString();
        assertThat(byGeneration).contains("/images/8-0.png").doesNotContain("/images/9-0.png").contains("href=\"/gallery?tag=estate\"")
                .contains("name=\"tag\"").contains("id=\"known-tags\"");
        assertThat(byFile).contains("/images/8-0.png").doesNotContain("/images/9-0.png");
        assertThat(favouritesByFile).contains("/images/8-1.png");
        assertThat(unknown).doesNotContain("/images/8-0.png").doesNotContain("/images/9-0.png");

        // il dettaglio mostra gli editor (generazione e file) e togliere un tag lo fa sparire dal filtro
        String detail = mockMvc.perform(get("/generations/" + g.getId())).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("id=\"gen-tags-" + g.getId() + "\"").contains("id=\"file-tags-" + g.getId() + "-8-0-png\"");
        mockMvc.perform(post("/generations/" + g.getId() + "/tags/remove").param("tag", "estate")).andExpect(status().isOk());
        assertThat(mockMvc.perform(get("/gallery").param("tag", "estate")).andReturn().getResponse().getContentAsString()).doesNotContain("/images/8-0.png");

        // un file che non e' della generazione e' un rifiuto, non un 500
        mockMvc.perform(post("/generations/" + g.getId() + "/tags/add").param("tag", "x").param("filename", "9-0.png"))
                .andExpect(status().isUnprocessableEntity());
    }

    /** Tag di conversazione: la sidebar mostra l'editor per la conversazione aperta e per quelle gia' taggate. */
    @Test
    @Transactional
    void conversationTagEndpointsReRenderTheSidebar() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        ChatConversation other = chatConversationRepository.save(new ChatConversation());

        String added = mockMvc.perform(post("/deep-chat/" + conversation.getId() + "/tags/add").param("tag", "Lavoro")
                        .param("activeConversationId", String.valueOf(other.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(added).contains("id=\"conversation-list-items\"").contains("id=\"conv-tags-" + conversation.getId() + "\"").contains("<span>lavoro</span>")
                .contains("id=\"conv-tags-" + other.getId() + "\"").contains("id=\"known-tags\"");
        String removed = mockMvc.perform(post("/deep-chat/" + conversation.getId() + "/tags/remove").param("tag", "lavoro")
                        .param("activeConversationId", String.valueOf(other.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(removed).doesNotContain("id=\"conv-tags-" + conversation.getId() + "\"").contains("id=\"conv-tags-" + other.getId() + "\"");
    }

    /** Rinominare la conversazione aperta aggiorna anche il titolo sopra la chat (OOB), una diversa no; la pagina intera non porta l'OOB. */
    @Test
    @Transactional
    void renamingTheActiveConversationUpdatesTheButtonTitle() throws Exception {
        ChatConversation active = chatConversationRepository.save(new ChatConversation());
        ChatConversation other = chatConversationRepository.save(new ChatConversation());

        String renamed = mockMvc.perform(post("/deep-chat/" + active.getId() + "/rename").param("title", "Nuovo titolo")
                        .param("activeConversationId", String.valueOf(active.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(renamed).contains("id=\"active-conversation-title\"").contains("hx-swap-oob=\"innerHTML\"").contains(">Nuovo titolo</span>");

        String renamedOther = mockMvc.perform(post("/deep-chat/" + other.getId() + "/rename").param("title", "Altro")
                        .param("activeConversationId", String.valueOf(active.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(renamedOther).contains(">Nuovo titolo</span>").doesNotContain(">Altro</span>\n");

        String page = mockMvc.perform(get("/deep-chat/" + active.getId())).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"active-conversation-title\"").doesNotContain("hx-swap-oob");
    }

    /** Le conversazioni stanno nella colonna collassabile del core (fragment collapsible-column), il titolo e il toggle sopra la chat. */
    @Test
    @Transactional
    void deepChatShowsConversationsInTheCollapsibleColumn() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());

        String page = mockMvc.perform(get("/deep-chat/" + conversation.getId())).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("x-data=\"collapsibleColumn\"").contains("data-storage-key=\"deepChat.conversationsOpen\"")
                .contains("Alpine.data('collapsibleColumn'")
                .contains("id=\"conversation-list-items\"").contains("@click=\"togglePanel()\"")
                .contains("/deep-chat/new").contains("id=\"deep-chat-el\"");
    }

    /** /system/events (e il vecchio /errors che reindirizza): pagina intera e frammento htmx, e "Svuota" cancella il registro. */
    @Test
    @Transactional
    void errorsPageListsRecordedErrorsAndClearEmptiesTheLog() throws Exception {
        systemEventRepository.save(new org.dual.hexa.core.events.domain.SystemEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.ERROR,
                org.dual.hexa.app.shared.domain.AppEventSource.REPLICATE, "getPrediction", "ReplicateException", "Replicate non risponde", "stack...",
                "generation:42", java.time.Instant.now()));

        String page = mockMvc.perform(get("/system/events")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fragment = mockMvc.perform(get("/system/events").header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Replicate non risponde").contains("getPrediction").contains("/generations/42");
        assertThat(fragment).contains("Replicate non risponde").doesNotContain("<html");

        String cleared = mockMvc.perform(post("/system/events/clear").header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cleared).doesNotContain("Replicate non risponde").contains("Nessun evento registrato");
        assertThat(systemEventRepository.count()).isZero();
    }

    private org.dual.hexa.core.events.domain.SystemEvent savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity severity, String message,
                                                              String subject) {
        return systemEventRepository.save(new org.dual.hexa.core.events.domain.SystemEvent(severity,
                org.dual.hexa.core.events.domain.CoreEventSource.TOKENS, "op", "T", message, null, subject,
                java.time.Instant.now().minusSeconds(300)));
    }

    /** La campanella: contenitore statico UNA volta nell'header, contenuto (badge+pannello) da GET /system/events/bell. */
    @Test
    void headerHasTheBellContainerOnceOutsideTheSlideover() throws Exception {
        String page = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String header = page.substring(page.indexOf("<header"), page.indexOf("</header>"));

        assertThat(header.split("id=\"notification-bell\"", -1)).hasSize(2);
        assertThat(header).contains("hx-get=\"/system/events/bell\"").contains("system-event from:body");
        assertThat(header.indexOf("id=\"notification-bell\"")).isLessThan(header.indexOf("x-show=\"navOpen\"")); // nella barra, non nello slideover
    }

    @Test
    @Transactional
    void bellIsOffWithoutUnseenEvents() throws Exception {
        systemEventRepository.deleteAll();

        String body = mockMvc.perform(get("/system/events/bell")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nessun nuovo evento").doesNotContain("bg-danger").doesNotContain("bg-warning");
    }

    @Test
    @Transactional
    void bellShowsUnseenCountSeverityColourAndLinksToTheEvent() throws Exception {
        systemEventRepository.deleteAll();
        var warning = savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.WARNING, "Il token scade tra 3 giorni", "token:1");

        String onlyWarning = mockMvc.perform(get("/system/events/bell")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(onlyWarning).contains("bg-warning").doesNotContain("bg-danger").contains("Il token scade tra 3 giorni")
                .contains("href=\"/system/events?event=" + warning.getId() + "\"").contains("Avviso");

        var error = savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.ERROR, "Replicate non risponde", null);
        String withError = mockMvc.perform(get("/system/events/bell")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(withError).contains("bg-danger").contains(">2</span>").contains("Replicate non risponde")
                .contains("href=\"/system/events?event=" + error.getId() + "\"");
    }

    @Test
    @Transactional
    void openingAnEventMarksOnlyThatOneAsSeenAndHighlightsIt() throws Exception {
        systemEventRepository.deleteAll();
        var a = savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.WARNING, "evento a", "token:1");
        var b = savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.WARNING, "evento b", "token:2");

        String page = mockMvc.perform(get("/system/events").param("event", String.valueOf(a.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"event-" + a.getId() + "\"").containsPattern("id=\"event-" + a.getId() + "\"[^>]*ring-2");
        assertThat(systemEventRepository.findById(a.getId()).orElseThrow().getAcknowledgedAt()).isNotNull();
        assertThat(systemEventRepository.findById(b.getId()).orElseThrow().getAcknowledgedAt()).isNull();
    }

    @Test
    @Transactional
    void markAllSeenClearsTheBellAndTellsTheListToRefresh() throws Exception {
        systemEventRepository.deleteAll();
        savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.WARNING, "evento", "token:1");

        var result = mockMvc.perform(post("/system/events/seen").header("HX-Request", "true")).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Nessun nuovo evento");
        assertThat(result.getResponse().getHeader("HX-Trigger")).contains("system-event");
    }

    @Test
    @Transactional
    void eventsPageFiltersBySeverityAndMarksUnreadRows() throws Exception {
        systemEventRepository.deleteAll();
        savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.WARNING, "solo avviso", "token:1");
        savedEvent(org.dual.hexa.core.events.domain.SystemEventSeverity.ERROR, "solo errore", null);

        String warnings = mockMvc.perform(get("/system/events").param("severity", "WARNING")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(warnings).contains("solo avviso").doesNotContain("solo errore").contains("Non visualizzato");

        String all = mockMvc.perform(get("/system/events")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("solo avviso").contains("solo errore").contains("Segna tutti come letti");

        String unknown = mockMvc.perform(get("/system/events").param("severity", "bogus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unknown).contains("solo avviso").contains("solo errore");
    }

    @Test
    void legacyErrorsPathRedirectsToSystemEvents() throws Exception {
        mockMvc.perform(get("/errors")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/system/events"));
    }

    /** Ogni pagina porta il contenitore dei toast e il link a /system/events nell'header. */
    @Test
    void layoutHasToastContainerAndErrorsNavLink() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("@system-toast.window").contains("href=\"/system/events\"")
                .doesNotContain("href=\"/search\""); // ricerca semantica spenta nei test: niente link a una pagina inesistente
    }

    /**
     * Azioni frequenti come link diretti (Deep Chat, Galleria), il resto in due menu (Crea/Gestione): gli stessi link stanno sia
     * nella barra sia nello slideover. "Archivio" non esiste piu'.
     */
    @Test
    void headerGroupsLinksIntoMenusInBarAndSlideover() throws Exception {
        String page = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String body = page.substring(page.indexOf("<header"), page.indexOf("</header>"));

        assertThat(body).contains("Crea").contains("Gestione").doesNotContain("Archivio").doesNotContain("Sistema")
                .contains("aria-haspopup=\"true\"");
        assertThat(body.split("href=\"/generations/new\\?kind=video\"", -1)).hasSize(3); // barra + slideover
        assertThat(body.split("href=\"/deep-chat\"", -1)).hasSize(3);
        assertThat(body.split("href=\"/gallery\"", -1)).hasSize(3);
        assertThat(body.split("href=\"/generations\"", -1)).hasSize(3);
        assertThat(body.split("href=\"/loras\"", -1)).hasSize(3);
        assertThat(body.split("href=\"/trainings\"", -1)).hasSize(3); // dal menu Crea: barra + slideover
        assertThat(body.split("href=\"/system/events\"", -1)).hasSize(3);
        assertThat(body.split("href=\"/tokens\"", -1)).hasSize(3); // da navSystemCore, composto dal nav dell'app
        assertThat(body.split("aria-haspopup=\"true\"", -1)).hasSize(5); // 2 menu x 2 contenitori
    }

    /** Estrae le breadcrumbs (il solo <nav> col loro aria-label) da una pagina. */
    private String breadcrumbsOf(String page) {
        int start = page.indexOf("<nav aria-label=\"Percorso\"");
        assertThat(start).as("breadcrumbs presenti").isGreaterThanOrEqualTo(0);
        return page.substring(start, page.indexOf("</nav>", start));
    }

    /** La Home porta ai punti d'ingresso principali (la ricerca solo se attiva) e non descrive lo stack ne' ha il footer con le tecnologie. */
    @Test
    void homeLinksTheMainEntryPointsWithoutTechnicalBlurbOrFooter() throws Exception {
        String page = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String content = page.substring(page.indexOf("<main"), page.indexOf("</main>"));

        assertThat(content).contains("href=\"/deep-chat\"", "href=\"/generations/new\"", "href=\"/generations/new?kind=video\"",
                        "href=\"/import\"", "href=\"/trainings\"", "href=\"/gallery\"")
                .doesNotContain("kind=edit")
                .doesNotContain("href=\"/search\"") // ricerca semantica spenta nei test
                .doesNotContain("Thymeleaf").doesNotContain("Spring MVC");
        assertThat(page).doesNotContain("<footer").doesNotContain("Spring Boot");
        // /import e' raggiungibile dalla Home (in <main>) e dal menu Crea (barra e slideover): almeno un link fuori da <main> oltre a quello della Home.
        assertThat(page.split("href=\"/import\"", -1).length - 1).isGreaterThan(1);
    }

    /** Ogni pagina tranne la Home mostra Home › [gruppo] › pagina, con la pagina corrente non linkata. */
    @Test
    void everyPageButHomeShowsBreadcrumbs() throws Exception {
        assertThat(mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString()).doesNotContain("aria-current=\"page\"");

        String gallery = breadcrumbsOf(mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(gallery).contains("href=\"/\"").contains("Galleria").containsPattern("aria-current=\"page\"[^>]*>Galleria<");

        String generations = breadcrumbsOf(mockMvc.perform(get("/generations")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(generations).contains("Gestione").containsPattern("aria-current=\"page\"[^>]*>Generazioni<");

        String image = breadcrumbsOf(mockMvc.perform(get("/generations/new")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(image).contains("Crea").containsPattern("aria-current=\"page\"[^>]*>Genera immagine<");
        String video = breadcrumbsOf(mockMvc.perform(get("/generations/new").param("kind", "video")).andReturn().getResponse().getContentAsString());
        assertThat(video).containsPattern("aria-current=\"page\"[^>]*>Genera video<");

        String imports = breadcrumbsOf(mockMvc.perform(get("/import")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(imports).contains("Crea").containsPattern("aria-current=\"page\"[^>]*>Importa immagini<");

        String training = breadcrumbsOf(mockMvc.perform(get("/trainings")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(training).contains("Crea").containsPattern("aria-current=\"page\"[^>]*>Addestra un LoRA<");

        String loras = breadcrumbsOf(mockMvc.perform(get("/loras")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(loras).contains("Gestione").containsPattern("aria-current=\"page\"[^>]*>LoRA<");
        String tokens = breadcrumbsOf(mockMvc.perform(get("/tokens")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tokens).contains("Gestione").containsPattern("aria-current=\"page\"[^>]*>Token<");
        String events = breadcrumbsOf(mockMvc.perform(get("/system/events")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(events).contains("Gestione").containsPattern("aria-current=\"page\"[^>]*>Eventi di sistema<");

        // Il manuale e' un link diretto del menu (nessun gruppo): l'indice e' la pagina corrente, una pagina ha l'indice come livello intermedio.
        String manual = breadcrumbsOf(mockMvc.perform(get("/manual")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(manual).doesNotContain("Gestione").containsPattern("aria-current=\"page\"[^>]*>Manuale<");
        String manualPage = breadcrumbsOf(mockMvc.perform(get("/manual/introduzione")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(manualPage).contains("href=\"/manual\"").containsPattern("aria-current=\"page\"[^>]*>Introduzione<");
    }

    @Test
    @Transactional
    void deepChatShowsBreadcrumbs() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String crumbs = breadcrumbsOf(mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(crumbs).containsPattern("aria-current=\"page\"[^>]*>Deep Chat<");
    }

    /** Il livello intermedio del dettaglio dipende da dove si arriva e sostituisce i vecchi link "Torna a...". */
    @Test
    @Transactional
    void generationDetailBreadcrumbFollowsTheOrigin() throws Exception {
        Generation g = new Generation("pred-bc", "owner/model", null, "p", null);
        g.setStatus(GenerationStatus.SUCCEEDED);
        g.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("bc-0.png")));
        g = repository.save(g);
        String url = "/generations/" + g.getId();

        String page = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(breadcrumbsOf(page)).contains("href=\"/gallery\"").containsPattern("aria-current=\"page\"[^>]*>Generazione #" + g.getId() + "<");
        assertThat(page).doesNotContain("Torna a");

        String fromList = breadcrumbsOf(mockMvc.perform(get(url).param("generationsPage", "2")).andReturn().getResponse().getContentAsString());
        assertThat(fromList).contains("href=\"/generations?page=2\"").contains("Generazioni");

        String fromChat = breadcrumbsOf(mockMvc.perform(get(url).param("conversationId", "7")).andReturn().getResponse().getContentAsString());
        assertThat(fromChat).contains("href=\"/deep-chat/7\"").contains("Deep Chat");
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
        long before = systemEventRepository.count();

        var result = mockMvc.perform(post("/generations/" + g.getId() + "/favourite")
                        .param("filename", "nope.png").header("HX-Request", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(result.getResponse().getHeader("HX-Trigger")).contains("system-toast").contains("\"message\"");
        assertThat(systemEventRepository.count()).isEqualTo(before);
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

    /** Ogni form-type con piu' immagini per richiesta ha lo stesso limite globale (4) nel campo, preso dalla costante dell'handler. */
    @Test
    void everyMultiOutputFormCapsNumOutputsAtTheGlobalLimit() throws Exception {
        for (String model : List.of("sdurz75/flux-lora-ff3", "black-forest-labs/flux-krea-dev", "black-forest-labs/flux-dev-lora")) {
            String body = mockMvc.perform(get("/generations/params").param("model", model))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).as(model).containsPattern("name=\"num_outputs\"[^>]*min=\"1\"[^>]*max=\"4\"");
        }
    }

    /** Il seed e' resettabile (torna a "casuale") in OGNI form-type: un solo fragment condiviso, con il bottone di reset che non sottomette il form. */
    @ParameterizedTest
    @ValueSource(strings = {"sdurz75/flux-lora-ff3", "black-forest-labs/flux-2-klein-9b", "black-forest-labs/flux-krea-dev",
            "prunaai/p-video", "black-forest-labs/flux-kontext-dev", "black-forest-labs/flux-dev-lora",
            "black-forest-labs/flux-fill-dev", "black-forest-labs/flux-fill-pro"})
    void everyFormTypeOffersAResetForTheSeed(String model) throws Exception {
        String body = mockMvc.perform(get("/generations/params").param("model", model))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countOccurrences(body, "id=\"param-seed\"")).as(model).isEqualTo(1);
        assertThat(body).as(model).containsPattern("<button type=\"button\"[^>]*aria-label=\"Torna a casuale\"");
    }

    @Autowired
    private org.dual.hexa.core.tokens.port.in.IApiTokens apiTokenService;

    @Autowired
    private org.dual.hexa.core.tokens.port.out.IApiTokenStore apiTokenRepository;

    @Autowired
    private org.dual.hexa.core.secrets.application.SecretCipher secretCipher;

    @Autowired
    private org.dual.hexa.app.generation.port.in.ILoraPresets loraPresetService;

    @Autowired
    private org.dual.hexa.app.generation.port.out.ILoraPresetStore loraPresetRepository;

    /**
     * Form-type FLUX_DEV_LORA (migrazione V20/FluxDevLoraParameterHandler): campi LoRA, upload img2img opzionale, solo i propri
     * campi (nessuno di quelli dei fine-tune LoRA) e i token SALVATI come select per nome (mai un campo per digitarli).
     */
    @Test
    @Transactional
    void paramsEndpointRendersFieldsForFluxDevLoraWithNamedTokenSelects() throws Exception {
        apiTokenRepository.deleteAll();
        var hf = apiTokenService.create("HUGGINGFACE", "Personale", "hf_super_secret_1234", null);
        var civitai = apiTokenService.create("CIVITAI", "Civitai lavoro", "cv_other_secret_5678",
                java.time.LocalDate.now().plusDays(400));
        apiTokenRepository.save(new org.dual.hexa.core.tokens.domain.ApiToken("CIVITAI", "Vecchio",
                secretCipher.encrypt("cv_old_secret_0000"), "0000", java.time.LocalDate.now().minusDays(1), java.time.Instant.now()));

        String body = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-dev-lora")
                        .param("hf_token_id", String.valueOf(hf.id())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("name=\"lora_weights\"", "name=\"lora_scale\"", "name=\"extra_lora\"",
                "name=\"extra_lora_scale\"", "name=\"prompt_strength\"", "name=\"sourceUpload\"",
                "name=\"aspect_ratio\"", "name=\"megapixels\"", "name=\"go_fast\"", "name=\"num_outputs\"");
        assertThat(body).doesNotContain("name=\"flux_model\"", "name=\"width\"", "value=\"match_input_image\"");
        // select Pines per nome: l'ID scelto e' preselezionato, il token scaduto non e' selezionabile, nessun segreto nel markup
        assertThat(body).contains("name=\"hf_token_id\"", "name=\"civitai_token_id\"", "Personale", "Civitai lavoro");
        assertThat(body).containsPattern("<option value=\"" + hf.id() + "\"[^>]*selected");
        assertThat(body).containsPattern("<option value=\"\"[^>]*>");
        assertThat(body).containsPattern("(?s)<option[^>]*value=\"" + civitai.id() + "\"[^>]*>[^<]*Civitai lavoro ••••5678 \\(scade il");
        assertThat(body).containsPattern("(?s)<option[^>]*disabled[^>]*>\\s*Vecchio ••••0000 \\(scaduto\\)");
        assertThat(body).doesNotContain("type=\"password\"").doesNotContain("hf_super_secret_1234")
                .doesNotContain("cv_other_secret_5678").doesNotContain("cv_old_secret_0000").doesNotContain("_secret_");
        assertThat(body).containsPattern("name=\"lora_scale\"[^>]*value=\"1(\\.0)?\"");
    }

    /**
     * I LoRA anagrafati (CRUD /loras) compaiono come select di preset sopra i due slot LoRA di flux-dev-lora: la select non ha
     * name (non viaggia col form), ogni opzione porta sorgente/intensita'/trigger words nei data-*, "testo libero" e' la prima.
     */
    @Test
    @Transactional
    void paramsEndpointRendersLoraPresetSelectsForFluxDevLora() throws Exception {
        loraPresetRepository.deleteAll();
        loraPresetService.create("Stile acquerello", "owner/acquerello", 0.8, "wtrclr style", null);

        String body = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-dev-lora"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("id=\"param-lora-preset\"", "id=\"param-extra-lora-preset\"", "Stile acquerello",
                "data-source=\"owner/acquerello\"", "data-trigger-words=\"wtrclr style\"", "testo libero", "href=\"/loras\"");
        assertThat(body).containsPattern("data-scale=\"0\\.8\"");
        assertThat(body).doesNotContainPattern("<select[^>]*id=\"param-lora-preset\"[^>]*name=");
        assertThat(body).doesNotContainPattern("<select[^>]*name=\"[^\"]*\"[^>]*id=\"param-(extra-)?lora-preset\"");
        // Ogni select e' legata ai campi del PROPRIO slot (x-init: dopo un restore si riposiziona sul preset con la stessa sorgente).
        assertThat(body).contains("data-source-field=\"param-lora-weights\"", "data-scale-field=\"param-lora-scale\"",
                "data-source-field=\"param-extra-lora\"", "data-scale-field=\"param-extra-lora-scale\"");
    }

    /** Anche flux-fill-dev (un solo slot LoRA) usa il fragment condiviso della select dei preset. */
    @Test
    @Transactional
    void paramsEndpointRendersTheLoraPresetSelectForFluxFillDev() throws Exception {
        loraPresetRepository.deleteAll();
        loraPresetService.create("Stile acquerello", "owner/acquerello", 0.8, "wtrclr style", null);

        String body = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-fill-dev"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("id=\"param-lora-preset\"", "data-source-field=\"param-lora-weights\"",
                "data-scale-field=\"param-lora-scale\"", "data-source=\"owner/acquerello\"");
        assertThat(body).doesNotContain("param-extra-lora-preset");
    }

    /** Gli altri form-type non hanno le select dei token (e non fanno la query dei token). */
    @Test
    void otherFormTypesHaveNoTokenSelects() throws Exception {
        String body = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-krea-dev"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hf_token_id").doesNotContain("civitai_token_id").doesNotContain("param-lora-preset");
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

    /** Il campo prompt ha sempre "Svuota il prompt": nella pagina e nella risposta di "AI enhance" (lo stesso fragment), nascosto via CSS a textarea vuota. */
    @Test
    void promptFieldAlwaysOffersAClearButton() throws Exception {
        String page = mockMvc.perform(get("/generations/new")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String enhanced = mockMvc.perform(post("/generations/enhance-prompt").param("prompt", "   "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        for (String body : new String[] {page, enhanced}) {
            assertThat(body).contains("title=\"Svuota il prompt\"").contains("peer-placeholder-shown:hidden");
            assertThat(body).containsPattern("(?s)<textarea[^>]*id=\"prompt\"[^>]*placeholder=\" \"[^>]*class=\"peer ");
        }
        // Cliccandolo sta nel form che spende su Replicate: type=button, mai un submit.
        assertThat(page).containsPattern("(?s)<button[^>]*type=\"button\"[^>]*title=\"Svuota il prompt\"");
    }

    /**
     * Nessuna pagina "senza conversazione": /deep-chat nudo risolve/crea
     * sempre quella di default e ci naviga (vedi DeepChatController).
     * @Transactional: senza, ogni esecuzione di questo test (e degli
     * altri due sotto che toccano CHAT_CONVERSATION/CHAT_MESSAGE)
     * lascerebbe righe nel Postgres di test condiviso da tutta la suite
     * (container unico, vedi PostgresTestContainerInitializer) - il
     * rollback automatico a fine test evita che si accumulino e
     * influenzino gli altri test.
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
     * fragments/app/gallery.html, vedi CLAUDE.md/il piano di questa feature)
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
        generation.setConversationId(conversation.getId());
        generation = repository.save(generation);

        chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.USER, "genera una volpe", null));
        chatMessageRepository.save(new ChatMessage(conversation, ChatMessageRole.AI, "ecco la volpe", generation.getId()));

        String body = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/images/dc-1.png");
        assertThat(body).doesNotContain("Nessuna immagine ancora in questa conversazione");
        // Overlay "Scarica" sul thumbnail (fragments/app/button-gen.html :: downloadOverlay).
        assertThat(body).contains("download=\"dc-1.png\"");
        // Il link di dettaglio della card contestuale porta il conversationId (vedi fragments/app/gallery-card.html), per il link "indietro" del dettaglio (fragments/app/generation.html :: status, ora su /generations/{id} - vedi CLAUDE.md).
        assertThat(body).contains("/generations/" + generation.getId() + "?conversationId=" + conversation.getId());

        ChatConversation otherConversation = chatConversationRepository.save(new ChatConversation());
        String otherBody = mockMvc.perform(get("/deep-chat/" + otherConversation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(otherBody).doesNotContain("/images/dc-1.png");
        assertThat(otherBody).contains("Nessuna immagine ancora in questa conversazione");
    }

    /**
     * La galleria contestuale mostra UNA card per OGNI file della conversazione (non solo il primo di ogni generazione), con la
     * selezione per file ("files" = "<idGenerazione>:<filename>") e senza il badge "+N"; la galleria globale resta una card per
     * generazione col badge.
     */
    @Test
    @Transactional
    void deepChatContextualGalleryShowsEveryFileOfAGeneration() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());

        Generation multi = new Generation("pred-dc-multi", "owner/model", null, "two guitars", null);
        multi.setStatus(GenerationStatus.SUCCEEDED);
        multi.setImageFilenames(List.of("dc-a.png", "dc-b.png"));
        multi.setConversationId(conversation.getId());
        multi = repository.save(multi);

        String body = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/images/dc-a.png").contains("/images/dc-b.png");
        assertThat(body).contains("name=\"files\"").contains("value=\"" + multi.getId() + ":dc-a.png\"")
                .contains("value=\"" + multi.getId() + ":dc-b.png\"");
        assertThat(body).contains("hx-post=\"/gallery/delete-selected-files\"").doesNotContain("hx-post=\"/gallery/delete-selected\"");
        assertThat(body).doesNotContain("pointer-events-none\">+1<");
        // Stesso contenuto dall'endpoint htmx richiamato a ogni nuovo messaggio.
        String fragment = mockMvc.perform(get("/deep-chat/" + conversation.getId() + "/gallery"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(fragment).contains("/images/dc-a.png").contains("/images/dc-b.png");

        // Galleria globale: una card per generazione (solo il primo file), col badge "+1" e la selezione per generazione.
        String global = mockMvc.perform(get("/gallery"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(global).contains("/images/dc-a.png").doesNotContain("/images/dc-b.png");
        assertThat(global).contains("value=\"" + multi.getId() + "\"").contains("hx-post=\"/gallery/delete-selected\"");
    }

    /**
     * Scroll infinito della galleria contestuale (popover di /deep-chat): 13 generazioni = 2 pagine da 12, piu' recenti prima; la prima ha la
     * sentinella `intersect` verso /deep-chat/{id}/gallery?page=2&more=true, la seconda (solo card, nessuna sentinella) porta la piu' vecchia.
     */
    @Test
    @Transactional
    void deepChatContextualGalleryPagesWithAnInfiniteScrollSentinel() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        for (int i = 1; i <= 13; i++) {
            Generation g = new Generation("pred-page-" + i, "owner/model", null, "p" + i, null);
            g.setStatus(GenerationStatus.SUCCEEDED);
            g.setImageFilenames(List.of("pg-" + i + ".png"));
            g.setConversationId(conversation.getId());
            repository.save(g);
        }
        String base = "/deep-chat/" + conversation.getId() + "/gallery";

        String first = mockMvc.perform(get(base).header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(first).contains("/images/pg-13.png").contains("/images/pg-2.png").doesNotContain("/images/pg-1.png");
        assertThat(first).contains(base + "?page=2&amp;more=true").contains("hx-trigger=\"intersect once\"");

        String second = mockMvc.perform(get(base).param("page", "2").param("more", "true").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(second).contains("/images/pg-1.png").doesNotContain("/images/pg-2.png").doesNotContain("more=true");
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
     * GalleryController#deleteSelected): il refresh SSE (fragments/app/gallery.html,
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
     * dettaglio (galleria con lightbox, vedi fragments/app/generation-images.html),
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
     * Il seed e' PER FILE nel dettaglio (Generation#seedOf), il prompt uno per generazione, e "Usa ..." non apre nessun caso d'uso: spinge
     * nello slot globale (data-shared-*, vedi fragments/app/generation-shared-slot.html). Seed per file se i log ne davano uno per output;
     * altrimenti quello del batch con l'etichetta "(batch)" se i file sono piu' d'uno; ignoto = "casuale" e nessun bottone seed.
     */
    @Test
    void generationDetailShowsSeedPerFileAndPushesToTheSharedSlot() throws Exception {
        Generation perFile = new Generation("pred-seed-1", "owner/model", null, "a cat", "{}", 100L);
        perFile.setStatus(GenerationStatus.SUCCEEDED);
        perFile.setImageFilenames(List.of("seed-a.png", "seed-b.png"));
        perFile.setImageSeeds(new java.util.LinkedHashMap<>(java.util.Map.of("seed-a.png", 111L, "seed-b.png", 222L)));
        perFile = repository.save(perFile);

        String perFileBody = mockMvc.perform(get("/generations/" + perFile.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(perFileBody).contains(">111<").contains(">222<")
                .contains("data-shared-seed=\"111\"").contains("data-shared-seed=\"222\"")
                .doesNotContain("data-shared-seed=\"100\"").doesNotContain("(batch)");
        assertThat(perFileBody).contains("data-shared-prompt=\"a cat\"");
        // Gli overlay "Anima"/"Modifica" restano link (?source=); spariscono solo quelli di riuso prompt/seed.
        assertThat(perFileBody).doesNotContain("/generations/new?prompt=").doesNotContain("/generations/new?seed=")
                .doesNotContain("/deep-chat?seed=");

        Generation batch = new Generation("pred-seed-2", "owner/model", null, "a dog", "{}", 777L);
        batch.setStatus(GenerationStatus.SUCCEEDED);
        batch.setImageFilenames(List.of("seed-c.png", "seed-d.png"));
        batch = repository.save(batch);

        String batchBody = mockMvc.perform(get("/generations/" + batch.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Un seed solo per il batch: riproduce solo la prima immagine, la seconda non mostra ne' seed ne' bottone.
        assertThat(batchBody).contains("(batch)").doesNotContain("non riproducibile da sola");
        assertThat(countOccurrences(batchBody, ">777<")).isEqualTo(1);
        assertThat(countOccurrences(batchBody, "data-shared-seed=\"777\"")).isEqualTo(1);

        Generation single = new Generation("pred-seed-3", "owner/model", null, "a fox", "{}", 5L);
        single.setStatus(GenerationStatus.SUCCEEDED);
        single.setImageFilenames(List.of("seed-e.png"));
        single = repository.save(single);

        String singleBody = mockMvc.perform(get("/generations/" + single.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(singleBody).contains("data-shared-seed=\"5\"").doesNotContain("(batch)");

        Generation unknown = new Generation("pred-seed-4", "owner/model", null, "a bird", null);
        unknown.setStatus(GenerationStatus.SUCCEEDED);
        unknown.setImageFilenames(List.of("seed-f.png"));
        unknown = repository.save(unknown);

        String unknownBody = mockMvc.perform(get("/generations/" + unknown.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknownBody).contains("casuale").doesNotContain("data-shared-seed");
        assertThat(unknownBody).contains("data-shared-prompt=\"a bird\"");
    }

    /** Il seed non e' piu' un parametro di /generations/new (ora passa dallo slot globale lato client): la query string e' ignorata. */
    @Test
    void generationFormNoLongerPrefillsSeedFromQueryParam() throws Exception {
        String body = mockMvc.perform(get("/generations/new").param("seed", "777"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainPattern("id=\"param-seed\"[^>]*value=\"777\"");
    }

    /**
     * Overlay "operazione in corso" generico (fragments/core/busy-overlay.html): e' nel layout di ogni pagina, il form di creazione porta il
     * proprio messaggio e non esiste piu' l'attributo opt-in {@code data-busy-overlay} (ora vale la regola "ogni non-GET blocca").
     */
    @Test
    void busyOverlayIsInEveryPageAndNoLongerOptIn() throws Exception {
        String body = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("role=\"alertdialog\"").contains("data-default-text=\"Operazione in corso...\"");
        assertThat(body).contains("data-busy-text=\"Avvio della generazione...\"");
        assertThat(body).contains("data-busy-text=\"Sto migliorando il prompt...\"").doesNotContain("data-busy-overlay");
    }

    /** Il prompt in query (usato da "Anima") pre-compila la textarea. */
    @Test
    void generationFormPrefillsPromptFromQueryParam() throws Exception {
        String body = mockMvc.perform(get("/generations/new").param("prompt", "a red fox"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern("<textarea[^>]*id=\"prompt\"[^>]*>a red fox</textarea>");
    }

    /**
     * Il link "indietro" del dettaglio dipende da dove si arriva (vedi
     * GenerationController#status): dalla galleria globale o da una
     * generazione appena creata (nessun param) torna a /gallery, dal
     * listato /generations (generationsPage) torna a quella pagina,
     * dalla galleria contestuale di una conversazione /deep-chat
     * (conversationId sulla query string, propagato da
     * fragments/app/gallery-card.html) torna a quella conversazione.
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
     * fragments/app/generation.html :: status, ramo SUCCEEDED/FAILED): dopo
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
     * riga DB in IGenerations#delete): risultato, un'immagine sparita
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
                new ChatMessage(conversation, ChatMessageRole.AI, "ecco la volpe", generation.getId()));

        mockMvc.perform(delete("/generations/" + generation.getId()))
                .andExpect(header().string("HX-Redirect", "/gallery"));

        assertThat(repository.findById(generation.getId())).isEmpty();
        assertThat(chatMessageRepository.findByConversation(conversation.getId()))
                .filteredOn(m -> m.getId().equals(message.getId()))
                .singleElement()
                .extracting(ChatMessage::getOutcomeRef)
                .isNull();
    }

    /**
     * Regressione: una tab che sta ancora pollando GET /generations/{id}
     * ogni 2s (vedi fragments/app/generation.html) non deve incappare in un
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
        repository.deleteAllById(List.of(htmxGeneration.getId()));

        mockMvc.perform(get("/generations/" + htmxGeneration.getId())
                        .param("generationsPage", "3")
                        .header("HX-Request", "true"))
                .andExpect(header().string("HX-Redirect", "/generations?page=3"));

        Generation browserGeneration = new Generation("pred-race-browser", "owner/model", null, "a wolf", null);
        browserGeneration.setStatus(GenerationStatus.PROCESSING);
        browserGeneration = repository.save(browserGeneration);
        repository.deleteAllById(List.of(browserGeneration.getId()));

        mockMvc.perform(get("/generations/" + browserGeneration.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/gallery"));
    }

    /**
     * Ramo in corso di fragments/app/generation.html :: status: placeholder con bottone
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

    /** Un riquadro per file richiesto (num_outputs), ma UN solo bottone di annullamento; senza num_outputs un riquadro. */
    @Test
    void inProgressStatusRendersOneTilePerRequestedOutputButASingleCancelButton() {
        Generation single = new Generation("pred-ph-2", "owner/model", null, "a fox", null);
        single.setStatus(GenerationStatus.PROCESSING);
        org.springframework.test.util.ReflectionTestUtils.setField(single, "id", 43L);
        Generation triple = new Generation("pred-ph-3", "owner/model", null, "a fox", "{\"num_outputs\":3,\"seed\":7}");
        triple.setStatus(GenerationStatus.PROCESSING);
        org.springframework.test.util.ReflectionTestUtils.setField(triple, "id", 44L);

        String one = renderStatus(single, false);
        assertThat(one.split("aspect-ratio:1/1", -1).length - 1).isEqualTo(1);
        assertThat(one.split("<button", -1).length - 1).isEqualTo(1);

        String three = renderStatus(triple, false);
        assertThat(three.split("aspect-ratio:1/1", -1).length - 1).isEqualTo(3);
        assertThat(three.split("<button", -1).length - 1).isEqualTo(1);
        assertThat(three).contains("hx-post=\"/generations/44/cancel");
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
        return templateEngine.process("fragments/app/generation", java.util.Set.of("status"), context);
    }

    /**
     * Griglia (globale o contestuale, stesso fragment fragments/app/gallery.html
     * :: grid): checkbox di selezione + bottone "Elimina selezionate"
     * disabilitato di default (nessuna selezione al primo caricamento,
     * vedi fragments/core/button.html :: dangerSelectable) devono comparire
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
        // cliccabile per un istante al primo paint, prima che Alpine inizializzi - vedi fragments/core/button.html.
        assertThat(body).containsPattern("<button[^>]*\\bdisabled\\b[^>]*hx-post=\"/gallery/delete-selected\"[^>]*>");
    }

    /**
     * L'endpoint di cancellazione in blocco cancella davvero righe e file
     * (vedi IGenerations#deleteAll), a differenza del bottone lato
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
     * Selezione per file della galleria contestuale (POST /gallery/delete-selected-files): toglie solo i file indicati, la generazione
     * resta se ne conserva almeno uno e sparisce se li perde tutti; voci malformate o sconosciute sono ignorate.
     */
    @Test
    void deleteSelectedFilesRemovesOnlyThoseFiles() throws Exception {
        Generation partial = new Generation("pred-bulkf-1", "owner/model", null, "a cat", null);
        partial.setStatus(GenerationStatus.SUCCEEDED);
        partial.setImageFilenames(List.of("bf-1.png", "bf-2.png", "bf-3.png"));
        partial = repository.save(partial);

        Generation whole = new Generation("pred-bulkf-2", "owner/model", null, "a dog", null);
        whole.setStatus(GenerationStatus.SUCCEEDED);
        whole.setImageFilenames(List.of("bf-4.png", "bf-5.png"));
        whole = repository.save(whole);

        mockMvc.perform(post("/gallery/delete-selected-files")
                        .param("files", partial.getId() + ":bf-2.png", whole.getId() + ":bf-4.png", whole.getId() + ":bf-5.png",
                                "garbage", ":nofile", "99999999:bf-1.png", partial.getId() + ":not-a-file.png", "x:bf-3.png"))
                .andExpect(status().isOk());

        assertThat(repository.findById(partial.getId()).orElseThrow().getImageFilenames()).containsExactly("bf-1.png", "bf-3.png");
        assertThat(repository.findById(whole.getId())).isEmpty();

        mockMvc.perform(post("/gallery/delete-selected-files")).andExpect(status().isOk());
        repository.deleteAllById(List.of(partial.getId()));
    }

    /**
     * La galleria contestuale di /deep-chat ascolta anche l'evento SSE
     * generico "gallery-update" (non solo "new-message"): una
     * cancellazione dalla griglia globale (o da un'altra conversazione)
     * deve riflettersi anche qui, vedi IGenerations#delete/#deleteAll
     * e GalleryPushNotifier#onGenerationsDeleted.
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
     * fragments/app/generations.html :: list): stesso principio di
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
     * fragments/app/generation-row.html): a differenza di delete-selected,
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
     * letterale - vedi fragments/core/layout.html).
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
     * Ogni selezione dell'app usa la select Pines (fragments/core/select.html): la <select> nativa resta nel DOM, ma sempre dentro il
     * wrapper x-data="pinesSelect", nascosta (sr-only) e con la UI sotto. Guardia STRUTTURALE sui sorgenti dei template (cosi'
     * copre anche pagine non renderizzabili nei test, come /search con app.search.enabled=false): una select nuda aggiunta in
     * futuro fa fallire questo test.
     */
    @Test
    void everySelectIsWrappedByThePinesSelectComponent() throws IOException {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath*:templates/**/*.html");
        assertThat(resources).isNotEmpty();
        int totalSelects = 0;
        for (var resource : resources) {
            if ("select.html".equals(resource.getFilename())) {
                continue;
            }
            String html = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("(?s)<!--.*?-->", "");
            var selects = java.util.regex.Pattern.compile("<select\\b").matcher(html).results().count();
            var wrappers = java.util.regex.Pattern.compile("x-data=\"pinesSelect\"").matcher(html).results().count();
            var hidden = java.util.regex.Pattern.compile("class=\"sr-only\" tabindex=\"-1\" aria-hidden=\"true\"").matcher(html).results().count();
            var uis = java.util.regex.Pattern.compile("fragments/core/select :: ui").matcher(html).results().count();
            assertThat(wrappers).as("wrapper pinesSelect in %s", resource.getFilename()).isEqualTo(selects);
            assertThat(hidden).as("select sr-only in %s", resource.getFilename()).isEqualTo(selects);
            assertThat(uis).as("UI del componente in %s", resource.getFilename()).isEqualTo(selects);
            totalSelects += (int) selects;
        }
        assertThat(totalSelects).isGreaterThanOrEqualTo(18);
    }

    /** Il componente Alpine e' caricato da layout.html prima del core Alpine, e la UI e' resa accanto alla select nativa. */
    @Test
    void layoutRegistersThePinesSelectComponentBeforeAlpineAndFormsRenderIt() throws Exception {
        String body = mockMvc.perform(get("/generations/new")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Alpine.data('pinesSelect'");
        assertThat(body.indexOf("Alpine.data('pinesSelect'")).isLessThan(body.indexOf("alpinejs@3.14.3"));
        assertThat(body).contains("x-data=\"pinesSelect\"", "role=\"listbox\"");
        assertThat(body).containsPattern("<select id=\"image-model-input\"[^>]*class=\"sr-only\"");
    }

    /**
     * Cattura una chiave aggiunta a un bundle e dimenticata nell'altro:
     * i due file devono avere esattamente lo stesso set di chiavi,
     * indipendentemente da quali pagine i test sopra esercitano
     * davvero.
     */
    @Test
    void messageBundlesHaveMatchingKeys() throws IOException {
        for (String basename : new String[]{"messages", "messages-core", "messages-ai"}) {
            Properties it = loadProperties("/" + basename + ".properties");
            Properties en = loadProperties("/" + basename + "_en.properties");

            assertThat(it.keySet()).as(basename).containsExactlyInAnyOrderElementsOf(en.keySet());
        }
    }

    /** Core, ai e app non definiscono la stessa chiave: niente shadowing silenzioso (chi sostituisce l'app non cambia le librerie). */
    @Test
    void coreAiAndAppBundlesDefineDisjointKeys() throws IOException {
        Properties app = loadProperties("/messages.properties");
        Properties core = loadProperties("/messages-core.properties");
        Properties ai = loadProperties("/messages-ai.properties");

        assertThat(app.keySet()).doesNotContainAnyElementsOf(core.keySet()).doesNotContainAnyElementsOf(ai.keySet());
        assertThat(ai.keySet()).doesNotContainAnyElementsOf(core.keySet());
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

    /** Kontext e flux-fill-* stanno nel combobox delle immagini (segnati come "richiede un'immagine") ma non in /deep-chat. */
    @Test
    @Transactional
    void sourceRequiredModelsAreOnTheImagePageWithARequiredUploadButNotInTheChat() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String images = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String video = mockMvc.perform(get("/generations/new").param("kind", "video"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String kontext = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-kontext-dev"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String chat = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(images).contains("black-forest-labs/flux-kontext-dev", "black-forest-labs/flux-fill-dev", "black-forest-labs/flux-fill-pro",
                "black-forest-labs/flux-krea-dev").doesNotContain("prunaai/p-video");
        assertThat(images).containsPattern("black-forest-labs/flux-kontext-dev · [^<]+<");
        assertThat(images).doesNotContain("flux-krea-dev ·").doesNotContain("kind=edit");
        assertThat(video).doesNotContain("black-forest-labs/flux-kontext-dev");
        // Il tag contiene un '>' dentro @change (f.size > ...): si cerca fino al '<' successivo, non al '>'.
        assertThat(kontext).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
        assertThat(kontext).contains("name=\"aspect_ratio\"").contains("match_input_image");
        assertThat(chat).doesNotContain("black-forest-labs/flux-kontext-dev");
    }

    /** flux-fill-pro: stesso editor della maschera, ma senza LoRA ne' numero di immagini (una prediction = un'immagine). */
    @Test
    @Transactional
    void fillProFormHasTheMaskEditorButNoLoraOrOutputCount() throws Exception {
        String pro = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-fill-pro"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(pro).contains("name=\"maskUpload\"").contains("x-data=\"maskEditor\"").contains("name=\"steps\"")
                .contains("name=\"guidance\"").contains("name=\"prompt_upsampling\"")
                .contains("name=\"output_format\"").contains("name=\"seed\"");
        assertThat(pro).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
        assertThat(pro).doesNotContain("name=\"lora_weights\"").doesNotContain("name=\"lora_scale\"").doesNotContain("name=\"num_outputs\"")
                .doesNotContain("name=\"megapixels\"").doesNotContain("name=\"outpaint\"").doesNotContain("name=\"mask\"")
                // La tolleranza di sicurezza e' forzata al massimo dal servizio: non e' esposta all'utente.
                .doesNotContain("safety_tolerance");
    }

    private Generation inpainting(String suffix, String sourceUpload, String sourceImage) {
        Generation source = new Generation("pred-inp-src-" + suffix, "owner/model", null, "a woman", null);
        source.setStatus(GenerationStatus.SUCCEEDED);
        source.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("src-" + suffix + ".png")));
        source = repository.save(source);
        Generation fill = new Generation("pred-inp-" + suffix, "black-forest-labs/flux-fill-dev", null, "a smiling face", null);
        fill.setStatus(GenerationStatus.SUCCEEDED);
        fill.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("out-" + suffix + ".png")));
        fill.setMaskUploadFilename("mask-" + suffix + ".png");
        fill.setSourceUploadFilename(sourceUpload);
        if (sourceImage != null) {
            fill.setSourceGenerationId(source.getId());
            fill.setSourceImageFilename(sourceImage);
        }
        return repository.save(fill);
    }

    /** Il dettaglio mostra la maschera SOPRA la sorgente (overlay col filtro SVG), non una miniatura bianco/nero a parte. */
    @Test
    @Transactional
    void detailShowsTheMaskOverTheSourceImage() throws Exception {
        Generation fromUpload = inpainting("up", "upload-up.png", null);
        Generation fromGeneration = inpainting("gen", null, "src-gen.png");

        String upload = mockMvc.perform(get("/generations/" + fromUpload.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String generation = mockMvc.perform(get("/generations/" + fromGeneration.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(upload).contains("/images/upload-up.png").contains("/images/mask-up.png").contains("id=\"mask-tint\"")
                .contains("filter: url(#mask-tint)").contains("opacity-50");
        assertThat(generation).contains("/images/src-gen.png").contains("/images/mask-gen.png").contains("id=\"mask-tint\"");
        // La vecchia miniatura bianco/nero (h-24 con bordo) non c'e' piu'.
        assertThat(upload).doesNotContain("h-24 w-auto rounded-md border");
    }

    /** Senza sapere su quale file e' stata dipinta (righe precedenti) non c'e' nulla su cui sovrapporre la maschera. */
    @Test
    @Transactional
    void detailHidesTheMaskWhenTheSourceFileIsUnknown() throws Exception {
        Generation old = inpainting("old", null, null);
        old.setSourceGenerationId(repository.save(inpainting("older", null, null)).getId()); // una riga vera: la pagina legge i tag (query = flush) e la FK deve reggere
        old = repository.save(old);

        String body = mockMvc.perform(get("/generations/" + old.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("/images/mask-old.png").doesNotContain("mask-tint");
    }

    /** Nel form la maschera si sovrappone all'anteprima della sorgente (evento `mask-changed` dell'editor), non e' una miniatura. */
    @Test
    @Transactional
    void imageFormOverlaysTheMaskOnTheSourcePreview() throws Exception {
        Generation image = new Generation("pred-ov-src", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("5-0.png")));
        image = repository.save(image);

        String fromGeneration = mockMvc.perform(get("/generations/new").param("kind", "image")
                        .param("source", String.valueOf(image.getId())).param("sourceImage", "5-0.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fromUpload = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-fill-dev"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fromGeneration).contains("@mask-changed.window=\"mask = $event.detail\"").contains("id=\"mask-tint\"");
        assertThat(fromUpload).contains("@mask-changed.window").contains("id=\"mask-tint\"").contains("preview &amp;&amp; mask")
                .doesNotContain("h-12 w-auto rounded border");
    }

    /** Inpainting: il modello sta nel combobox delle immagini, con editor maschera e un solo LoRA (senza token); non compare in chat. */
    @Test
    @Transactional
    void inpaintingModelOffersTheMaskEditorOnTheImagePageButNotInTheChat() throws Exception {
        ChatConversation conversation = chatConversationRepository.save(new ChatConversation());
        String images = mockMvc.perform(get("/generations/new"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String chat = mockMvc.perform(get("/deep-chat/" + conversation.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fill = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-fill-dev"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(images).contains("black-forest-labs/flux-fill-dev").contains("black-forest-labs/flux-fill-pro")
                .contains("black-forest-labs/flux-kontext-dev");
        // Il componente Alpine e' registrato a livello di pagina (il fragment dei campi viene sostituito al cambio modello).
        assertThat(images).contains("Alpine.data('maskEditor'").contains("function featherAlpha(");
        assertThat(chat).doesNotContain("black-forest-labs/flux-fill-dev").doesNotContain("black-forest-labs/flux-fill-pro");
        // Il modello di default della chat (un fine-tune LoRA) ha la maschera opzionale: nel pannello l'editor non deve esistere (il componente
        // `maskEditor` non e' registrato li'), quindi sta in un <template x-if> che non si istanzia e non entra nel FormData.
        assertThat(chat).containsPattern("(?s)<template x-if=\"!\\$el\\.closest\\('#generation-settings-panel'\\)\">\\s*<div[^>]*x-data=\"maskEditor\"");

        assertThat(fill).contains("name=\"maskUpload\"").contains("x-data=\"maskEditor\"").contains("data-action=\"brush\"")
                .contains("data-action=\"eraser\"").contains("data-action=\"ellipse\"").contains("data-action=\"undo\"")
                .contains("name=\"lora_weights\"").contains("name=\"lora_scale\"").contains("match_input")
                // Sfumatura dei bordi della maschera (slider + anteprima live nel canvas).
                .contains("min=\"0\" max=\"25\" step=\"1\" x-model.number=\"feather\"").contains("blurCss()")
                // Valori in pixel di tratto e sfumatura e dimensioni dell'immagine, sempre visibili nell'editor.
                .contains("x-text=\"diameterLabel()\"").contains("x-text=\"featherLabel()\"")
                .contains("imgW + ' x ' + imgH + ' px'");
        assertThat(fill).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
        // Un solo LoRA e nessun token: il modello non ha extra_lora ne' hf_api_token/civitai_api_token.
        assertThat(fill).doesNotContain("name=\"extra_lora\"").doesNotContain("hf_token_id").doesNotContain("civitai_token_id");
        // La maschera e' sempre un file: nessun campo di testo la porta (finirebbe in localStorage).
        assertThat(fill).doesNotContain("name=\"mask\"");
    }

    /** Un fine-tune LoRA (flux-lora-ff3, text-to-image) offre nella sua pagina sorgente e maschera OPZIONALI, con prompt_strength: senza `required`. */
    @Test
    @Transactional
    void loraFinetuneOffersOptionalSourceAndMaskOnTheImagePage() throws Exception {
        String images = mockMvc.perform(get("/generations/params").param("model", "sdurz75/flux-lora-ff3"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(images).contains("name=\"maskUpload\"").contains("x-data=\"maskEditor\"").contains("name=\"prompt_strength\"");
        // Con un'immagine il modello ignora width/height: la dimensione la decide megapixels (enum 0.25/1), esposto nello stesso blocco.
        assertThat(images).containsPattern("(?s)<select[^>]*name=\"megapixels\".*?value=\"0.25\".*?value=\"1\".*?</select>");
        assertThat(images).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"");
        assertThat(images).doesNotContainPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
        assertThat(images).doesNotContain("name=\"mask\"");
    }

    /** "Usa come sorgente" da una generazione: preseleziona un modello immagine con sorgente opzionale (mai kontext/fill, mai p-video), porta la sorgente, niente upload. */
    @Test
    @Transactional
    void imageFormWithSourceCarriesTheSourceAndPreselectsAnImg2ImgModel() throws Exception {
        Generation image = new Generation("pred-edit-src", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("1-0.png")));
        image = repository.save(image);

        String body = mockMvc.perform(get("/generations/new").param("kind", "image")
                        .param("source", String.valueOf(image.getId())).param("sourceImage", "1-0.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("name=\"sourceUpload\"");
        assertThat(body).containsPattern("name=\"sourceGenerationId\"[^>]*value=\"" + image.getId() + "\"");
        assertThat(body).containsPattern("<option value=\"(sdurz75/flux-lora-ff3|black-forest-labs/flux-dev-lora)\"[^>]*selected");
        assertThat(body).doesNotContainPattern("<option value=\"black-forest-labs/flux-kontext-dev\"[^>]*selected");
        assertThat(body).doesNotContain("prunaai/p-video");
        // Il vecchio link della pagina di modifica porta alla stessa pagina.
        assertThat(mockMvc.perform(get("/generations/new").param("kind", "edit")
                        .param("source", String.valueOf(image.getId())).param("sourceImage", "1-0.png"))
                .andReturn().getResponse().getContentAsString()).containsPattern("<option value=\"(sdurz75/flux-lora-ff3|black-forest-labs/flux-dev-lora)\"[^>]*selected");
    }

    /**
     * Con una sorgente da generazione il cambio modello (GET /generations/params) NON ripropone l'upload obbligatorio: la sorgente viaggia
     * con la richiesta (hidden sourceGenerationId/sourceImage, fuori da #generation-params-fields) e il server la rivalida. Senza (o non
     * valida) l'upload obbligatorio resta, e' il caso stand-alone.
     */
    @Test
    @Transactional
    void paramsWithAGenerationSourceDoesNotAskForAnUpload() throws Exception {
        Generation image = new Generation("pred-params-src", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("5-0.png")));
        image = repository.save(image);
        String fillDev = "black-forest-labs/flux-fill-dev";

        String withSource = mockMvc.perform(get("/generations/params").param("model", fillDev)
                        .param("sourceGenerationId", String.valueOf(image.getId())).param("sourceImage", "5-0.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String withoutSource = mockMvc.perform(get("/generations/params").param("model", fillDev))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String wrongFile = mockMvc.perform(get("/generations/params").param("model", fillDev)
                        .param("sourceGenerationId", String.valueOf(image.getId())).param("sourceImage", "altro.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // La maschera resta (l'editor legge la sorgente dall'anteprima della pagina), l'upload no.
        assertThat(withSource).doesNotContain("name=\"sourceUpload\"").contains("name=\"maskUpload\"");
        assertThat(withoutSource).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
        assertThat(wrongFile).containsPattern("(?s)<input[^<]*name=\"sourceUpload\"[^<]*\\brequired");
    }

    /** La pagina con sorgente da generazione fa viaggiare la sorgente anche nella select modello e nel "Reimposta ai default". */
    @Test
    @Transactional
    void imagePageWithSourceSendsTheSourceWithEveryParamsRequest() throws Exception {
        Generation image = new Generation("pred-params-url", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("6-0.png")));
        image = repository.save(image);

        String withSource = mockMvc.perform(get("/generations/new").param("kind", "image")
                        .param("source", String.valueOf(image.getId())).param("sourceImage", "6-0.png"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String standalone = mockMvc.perform(get("/generations/new").param("kind", "image"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(withSource).contains("hx-include=\"#generation-params-fields, [name=sourceGenerationId], [name=sourceImage]\"")
                .containsPattern("hx-get=\"[^\"]*/generations/params\\?model=[^\"]*sourceGenerationId=" + image.getId() + "[^\"]*sourceImage=6-0\\.png");
        // Stand-alone: nessuna sorgente da portare nel reset.
        assertThat(standalone).doesNotContain("sourceGenerationId=");
        // Il restore dello script di persistenza chiede i campi con la stessa sorgente.
        assertThat(withSource).contains("function sourceQuery(form)");
    }

    /**
     * Fra modelli di modifica guidance e passi NON si ereditano (scala propria: 2.5 di kontext e' invalido per lo step=1 di fill-dev e ne
     * blocca il submit), mentre i campi davvero comuni si'. Nello stesso modello (re-render dopo un create rifiutato) si conservano.
     */
    @Test
    @Transactional
    void switchingModelDoesNotInheritGuidanceOrStepsButKeepsTheOtherCommonFields() throws Exception {
        String fillDev = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-fill-dev")
                        .param("guidance", "2.5").param("num_inference_steps", "4").param("output_quality", "70"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String kontext = mockMvc.perform(get("/generations/params").param("model", "black-forest-labs/flux-kontext-dev")
                        .param("guidance", "60").param("num_inference_steps", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fillDev).containsPattern("id=\"param-guidance\"[^>]*value=\"30(\\.0)?\"")
                .containsPattern("id=\"param-steps\"[^>]*value=\"28\"")
                .containsPattern("id=\"param-output-quality\"[^>]*value=\"70\"");
        assertThat(kontext).containsPattern("id=\"param-guidance\"[^>]*value=\"2\\.5\"")
                .containsPattern("id=\"param-steps\"[^>]*value=\"28\"");
    }

    /** Ogni thumbnail immagine offre "Usa come sorgente" (img2img); non c'e' piu' un overlay "Modifica" a parte. */
    @Test
    @Transactional
    void galleryCardOffersUseAsSourceForImagesAndNoSeparateEdit() throws Exception {
        Generation image = new Generation("pred-edit-card", "owner/model", null, "a cat", null);
        image.setStatus(GenerationStatus.SUCCEEDED);
        image.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("2-0.png")));
        image = repository.save(image);

        String body = mockMvc.perform(get("/gallery")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("kind=image").contains("source=" + image.getId()).doesNotContain("kind=edit");
    }
}
