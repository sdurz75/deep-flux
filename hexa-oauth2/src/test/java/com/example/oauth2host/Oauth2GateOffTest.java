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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Il contratto piu' importante di hexa-oauth2: a cancello spento (default) la libreria e' INVISIBILE. Nessuna richiesta cambia risposta, i POST senza
 * token CSRF passano, nessuna intestazione di sicurezza viene aggiunta e nessuna password generata blocca nulla.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.secrets.expiry-check-enabled=false"})
@AutoConfigureMockMvc
class Oauth2GateOffTest {

    @Autowired MockMvc mockMvc;
    @Autowired IOAuthAccess access;
    @Autowired JdbcTemplate jdbc;

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
    void theSectionRegistersInTheSettingsPageAndRendersItsFieldsAndStatus() throws Exception {
        String page = mockMvc.perform(get("/settings")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("id=\"settings-oauth2\"", "Cancello di accesso", "name=\"enabled\"", "name=\"allowed-emails\"", "name=\"providers.__new.client-secret\"",
                "oauth2-extra").doesNotContain("??");
    }

    @Test
    void theGateCannotBeTurnedOnWithoutProviderAllowedUsersAndATestLogin() throws Exception {
        String body = mockMvc.perform(post("/settings/oauth2").header("HX-Request", "true").param("enabled", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Aggiungi prima almeno un provider");
        assertThat(access.isEnabled()).isFalse();
    }

    @Test
    void aProviderWithABadIssuerIsRefusedAndAGoodOneIsSavedWithItsSecretOutOfTheDatabaseAndThePage() throws Exception {
        String bad = mockMvc.perform(post("/settings/oauth2").header("HX-Request", "true").param("providers.__new.slug", "kc")
                        .param("providers.__new.title", "Keycloak").param("providers.__new.issuer-uri", "http://evil.example/realm")
                        .param("providers.__new.client-id", "app").param("providers.__new.client-secret", "very-SECRET-1234"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bad).contains("https").doesNotContain("very-SECRET");

        try {
            String ok = mockMvc.perform(post("/settings/oauth2").header("HX-Request", "true").param("providers.__new.slug", "kc")
                            .param("providers.__new.title", "Keycloak").param("providers.__new.issuer-uri", "https://sso.example/realms/x/")
                            .param("providers.__new.client-id", "app").param("providers.__new.client-secret", "very-SECRET-1234"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(ok).contains("Keycloak", "…1234", "/login/oauth2/code/kc").doesNotContain("very-SECRET");
            assertThat(jdbc.queryForObject("select count(*) from module_config where config_value like '%SECRET%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from secret where type = 'OAUTH2_CLIENT' and name = 'oauth2/providers/kc/client-secret'", Integer.class)).isEqualTo(1);
        } finally {
            mockMvc.perform(post("/settings/oauth2/reset"));
        }
        assertThat(jdbc.queryForObject("select count(*) from secret where type = 'OAUTH2_CLIENT'", Integer.class)).isZero();
    }
}
