package org.dual.replicate.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.dual.replicate.domain.ChatConversation;
import org.dual.replicate.domain.ChatMessage;
import org.dual.replicate.domain.ChatMessageRole;
import org.dual.replicate.domain.Generation;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
    private MockMvc mockMvc;

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
     * Nuova validazione introdotta col catalogo modelli in DB (vedi il
     * piano di questa feature): GenerationController.create ora rifiuta
     * un modello non censito PRIMA di chiamare Replicate, invece di
     * accettare qualunque stringa come faceva la vecchia versione
     * "testo libero" del combobox.
     */
    @Test
    void createWithUnknownModelIsRejectedInline() throws Exception {
        String body = mockMvc.perform(post("/generations")
                        .header("HX-Request", "true")
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

    @Test
    void paramsEndpointReturnsNotFoundForUnknownModel() throws Exception {
        mockMvc.perform(get("/generations/params").param("model", "owner/does-not-exist"))
                .andExpect(status().isNotFound());
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

    @Test
    void galleryDetailAndStatusRenderForSucceededGeneration() throws Exception {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", "{\"seed\":1}");
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("1.png"));
        generation = repository.save(generation);

        mockMvc.perform(get("/gallery")).andExpect(status().isOk());
        mockMvc.perform(get("/gallery").header("HX-Request", "true")).andExpect(status().isOk());
        mockMvc.perform(get("/gallery/" + generation.getId())).andExpect(status().isOk());
        // Terminale: refresh() ritorna subito, nessuna chiamata a Replicate.
        mockMvc.perform(get("/generations/" + generation.getId())).andExpect(status().isOk());
    }

    /**
     * L'eliminazione vive ora solo nella pagina di dettaglio (vedi
     * gallery-detail.html/fragments/gallery-card.html): dopo la
     * cancellazione la pagina corrente non esiste piu', quindi entrambi
     * i rami del controller devono portare il browser a /gallery,
     * l'htmx via l'header HX-Redirect (non uno swap di contenuto), il
     * non-htmx via un vero redirect HTTP.
     */
    @Test
    void deleteViaHtmxSetsHxRedirectHeader() throws Exception {
        Generation generation = new Generation("pred-3", "owner/model", null, "a bird", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("3.png"));
        generation = repository.save(generation);

        mockMvc.perform(post("/gallery/" + generation.getId() + "/delete").header("HX-Request", "true"))
                .andExpect(header().string("HX-Redirect", "/gallery"));
    }

    @Test
    void deleteWithoutHtmxRedirectsToGallery() throws Exception {
        Generation generation = new Generation("pred-4", "owner/model", null, "a fish", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("4.png"));
        generation = repository.save(generation);

        mockMvc.perform(post("/gallery/" + generation.getId() + "/delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/gallery"));
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
}
