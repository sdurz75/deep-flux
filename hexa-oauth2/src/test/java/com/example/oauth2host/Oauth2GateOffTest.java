package com.example.oauth2host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Il contratto piu' importante di hexa-oauth2: a cancello spento (default) la libreria e' INVISIBILE. Nessuna richiesta cambia risposta, i POST senza
 * token CSRF passano, nessuna intestazione di sicurezza viene aggiunta e nessuna password generata blocca nulla.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.tokens.expiry-check-enabled=false"})
@AutoConfigureMockMvc
class Oauth2GateOffTest {

    @Autowired MockMvc mockMvc;
    @Autowired IOAuthAccess access;

    @Test
    void theGateIsOffByDefaultAndEveryRequestPassesUnchanged() throws Exception {
        assertThat(access.isEnabled()).isFalse();

        var response = mockMvc.perform(get("/").accept("text/html")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("X-Frame-Options")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isNull();
        mockMvc.perform(get("/data")).andExpect(status().isOk()).andExpect(content().string("data"));
    }

    @Test
    void aPostWithoutCsrfTokenOrSameOriginHeadersIsNotRejected() throws Exception {
        mockMvc.perform(post("/ping").header("Sec-Fetch-Site", "cross-site").header("HX-Request", "true")).andExpect(status().isOk());
    }

    @Test
    void theLoginPageRendersAndSaysThatNoProviderIsConfigured() throws Exception {
        String body = mockMvc.perform(get("/oauth2/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nessun provider configurato").doesNotContain("??");
    }

    @Test
    void theAdminPageRegistersInTheManageMenuAndRenders() throws Exception {
        String page = mockMvc.perform(get("/oauth2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Cancello di accesso", "Utenti ammessi", "oauth2-panel").doesNotContain("??");
        String fragment = mockMvc.perform(get("/oauth2").header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fragment).startsWith("<div").contains("id=\"oauth2-panel\"").doesNotContain("<html");
    }

    @Test
    void theGateCannotBeTurnedOnWithoutProviderAllowedUsersAndATestLogin() throws Exception {
        String body = mockMvc.perform(post("/oauth2/gate/enable").header("HX-Request", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Aggiungi prima almeno un provider");
        assertThat(access.isEnabled()).isFalse();
    }
}
