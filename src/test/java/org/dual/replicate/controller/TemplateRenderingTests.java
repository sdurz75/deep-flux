package org.dual.replicate.controller;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.repository.GenerationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

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
}
