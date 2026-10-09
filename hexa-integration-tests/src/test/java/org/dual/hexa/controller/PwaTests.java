package org.dual.hexa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** hexa-pwa insieme al core sull'host di prova: ogni pagina dichiara il manifest, il manifest ha il nome dell'app, il worker non mette in cache nulla di dinamico. */
@SpringBootTest
@AutoConfigureMockMvc
class PwaTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyPageAdvertisesTheManifestAndRegistersTheWorker() throws Exception {
        String home = mockMvc.perform(get("/secrets")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(home).contains("rel=\"manifest\" href=\"/manifest.webmanifest\"", "name=\"theme-color\"", "data-sw=\"/sw.js\"");
    }

    @Test
    void theManifestCarriesTheAppNameInBothLanguages() throws Exception {
        mockMvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Host di prova"));
        mockMvc.perform(get("/manifest.webmanifest").header("Accept-Language", "en")).andExpect(jsonPath("$.name").value("Test host"))
                .andExpect(jsonPath("$.short_name").value("Host"));
    }

    /** Le uniche cose che il worker serve da solo: la shell offline e gli asset statici/CDN. HTML dinamico, SSE e binari passano sempre dalla rete. */
    @Test
    void theWorkerNeverCachesDynamicHtmlEventsOrImages() throws Exception {
        String worker = mockMvc.perform(get("/sw.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(worker).contains("request.method !== 'GET'", "HX-Request", "request.mode === 'navigate'")
                .contains("const STATIC_PREFIXES = ['js/', 'css/', 'pwa/'];");
    }

    /** Il blocco con PIN e' del core (non della PWA): la pagina Sicurezza sta nel menu Gestione e senza PIN l'app e' libera. */
    @Test
    void theSecurityPageIsReachableFromTheManageMenuAndTheAppIsFreeWithoutAPin() throws Exception {
        String page = mockMvc.perform(get("/security")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Gestione", "Sicurezza", "action=\"/security/pin\"").doesNotContain("id=\"lock-idle\"");
        assertThat(mockMvc.perform(get("/secrets")).andReturn().getResponse().getContentAsString()).contains("href=\"/security\"");
    }
}
