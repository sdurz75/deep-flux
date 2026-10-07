package com.example.pwahost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prova che hexa-core + hexa-pwa si autoconfigurano in un host fuori da {@code org.dual.hexa}: manifest, service worker, pagina offline, innesto nel
 * {@code <head>} del layout e bundle {@code messages-pwa}. Config dell'host: solo {@code core.yml}; hexa-pwa non ha nulla da importare.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.tokens.expiry-check-enabled=false"})
@AutoConfigureMockMvc
class PwaHostTest {

    @Autowired MockMvc mockMvc;

    @Test
    void theManifestIsServedWithNamesFromTheHostBundleAndAScopeUnderTheContextPath() throws Exception {
        var response = mockMvc.perform(get("/sub/manifest.webmanifest").contextPath("/sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Host di prova"))
                .andExpect(jsonPath("$.short_name").value("Host"))
                .andExpect(jsonPath("$.start_url").value("/sub/"))
                .andExpect(jsonPath("$.scope").value("/sub/"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.icons[?(@.purpose=='maskable')].src").value("/sub/pwa/icons/icon-maskable-512.png"))
                .andReturn().getResponse();

        assertThat(response.getContentType()).startsWith("application/manifest+json");
        assertThat(response.getHeader("Cache-Control")).contains("no-cache");
    }

    @Test
    void theManifestFollowsTheRequestLanguage() throws Exception {
        mockMvc.perform(get("/manifest.webmanifest").header("Accept-Language", "en"))
                .andExpect(jsonPath("$.name").value("Test host"));
    }

    @Test
    void theServiceWorkerIsRenderedForTheScopeAndNeverCached() throws Exception {
        var response = mockMvc.perform(get("/sub/sw.js").contextPath("/sub")).andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getContentType()).startsWith("text/javascript");
        assertThat(response.getHeader("Cache-Control")).contains("no-cache");
        assertThat(response.getContentAsString()).contains("const SCOPE = '/sub/';", "const OFFLINE_URL = '/sub/offline';")
                .doesNotContain("__SCOPE__", "__OFFLINE_URL__", "__CACHE_NAME__");
    }

    @Test
    void theOfflinePageRendersWithTheLayoutAndItsOwnBundle() throws Exception {
        String body = mockMvc.perform(get("/offline")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Sei offline", "<aside").doesNotContain("??");
    }

    @Test
    void everyPageHeadAdvertisesTheManifestAndRegistersTheWorker() throws Exception {
        String head = mockMvc.perform(get("/sub/").contextPath("/sub")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(head).contains("rel=\"manifest\" href=\"/sub/manifest.webmanifest\"", "name=\"theme-color\"", "rel=\"apple-touch-icon\"",
                "data-sw=\"/sub/sw.js\"", "data-scope=\"/sub/\"");
    }
}
