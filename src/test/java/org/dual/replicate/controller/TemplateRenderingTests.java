package org.dual.replicate.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.GenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void generationFormRenders() throws Exception {
        mockMvc.perform(get("/generations/new")).andExpect(status().isOk());
    }

    @Test
    void deepChatPageRenders() throws Exception {
        mockMvc.perform(get("/deep-chat")).andExpect(status().isOk());
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
        generation.setImageFilename("1.png");
        generation = repository.save(generation);

        mockMvc.perform(get("/gallery")).andExpect(status().isOk());
        mockMvc.perform(get("/gallery").header("HX-Request", "true")).andExpect(status().isOk());
        mockMvc.perform(get("/gallery/" + generation.getId())).andExpect(status().isOk());
        // Terminale: refresh() ritorna subito, nessuna chiamata a Replicate.
        mockMvc.perform(get("/generations/" + generation.getId())).andExpect(status().isOk());
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
