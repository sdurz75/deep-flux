package org.dual.replicate.controller;

import org.dual.replicate.repository.ApiTokenRepository;
import org.dual.replicate.repository.SystemEventRepository;
import org.dual.replicate.service.ApiTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD /tokens: pagina, dialog, validazione col retarget del form, e il token in chiaro non compare in NESSUNA risposta. */
@SpringBootTest
@AutoConfigureMockMvc
class TokenControllerTest {

    private static final String SECRET = "hf_super_secret_value_ABCD";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiTokenRepository repository;
    @Autowired
    private SystemEventRepository eventRepository;
    @Autowired
    private ApiTokenService service;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        eventRepository.deleteAll();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET).doesNotContain("super_secret");
        return body;
    }

    @Test
    void pageRendersTheEmptyListTheDialogAndTheNavLink() throws Exception {
        String page = mockMvc.perform(get("/tokens")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Nessun token salvato").contains("id=\"token-form\"").contains("id=\"token-list\"")
                .contains("hx-get=\"/tokens/new\"").contains("href=\"/tokens\"");
        assertThat(page).doesNotContain("Chiave di cifratura mancante"); // la chiave di test e' configurata (pom.xml)
    }

    @Test
    void newFormHasProviderSelectNameTokenPasswordAndDateButNoValues() throws Exception {
        String form = body(get("/tokens/new"));

        assertThat(form).contains("name=\"provider\"", "name=\"name\"", "name=\"expiresAt\"", "type=\"date\"")
                .containsPattern("<input type=\"password\"[^>]*name=\"token\"[^>]*required");
        assertThat(form).contains("x-data=\"pinesSelect\"");
    }

    @Test
    void createValidatesWithRetargetAndSavesWithTheSavedTriggerAndAnUpdatedList() throws Exception {
        var invalid = mockMvc.perform(post("/tokens").header("HX-Request", "true").param("provider", "HUGGINGFACE").param("name", " ")
                        .param("token", SECRET)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(invalid.getHeader("HX-Retarget")).isEqualTo("#token-form");
        assertThat(invalid.getHeader("HX-Reswap")).isEqualTo("outerHTML");
        assertThat(invalid.getHeader("HX-Trigger")).isNull();
        assertThat(invalid.getContentAsString()).contains("Il nome").doesNotContain(SECRET);
        assertThat(repository.count()).isZero();

        var ok = mockMvc.perform(post("/tokens").header("HX-Request", "true").param("provider", "HUGGINGFACE").param("name", "Personale")
                        .param("token", SECRET).param("expiresAt", "")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ok.getHeader("HX-Trigger")).contains("token-saved");
        assertThat(ok.getHeader("HX-Retarget")).isNull();
        assertThat(ok.getContentAsString()).contains("Personale").contains("…ABCD").contains("HuggingFace").contains("Nessuna scadenza")
                .doesNotContain(SECRET);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void duplicateNameAndInvalidDateAreFormErrors() throws Exception {
        service.create("HUGGINGFACE", "Personale", SECRET, null);

        var duplicate = mockMvc.perform(post("/tokens").header("HX-Request", "true").param("provider", "HUGGINGFACE")
                .param("name", "personale").param("token", "x")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(duplicate.getHeader("HX-Retarget")).isEqualTo("#token-form");
        assertThat(duplicate.getContentAsString()).contains("Esiste gia").contains("chiamato &quot;personale&quot;");

        var badDate = mockMvc.perform(post("/tokens").header("HX-Request", "true").param("provider", "CIVITAI")
                .param("name", "Altro").param("token", "x").param("expiresAt", "31/12/2026")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(badDate.getHeader("HX-Retarget")).isEqualTo("#token-form");
        assertThat(badDate.getContentAsString()).contains("data valida");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void editFormIsPrefilledWithoutTheTokenAndUpdateKeepsItWhenLeftBlank() throws Exception {
        var created = service.create("HUGGINGFACE", "Personale", SECRET, java.time.LocalDate.now().plusDays(200));

        String form = body(get("/tokens/" + created.id() + "/edit"));
        assertThat(form).contains("value=\"Personale\"").contains("value=\"" + created.expiresAt() + "\"").doesNotContain("name=\"provider\"");
        assertThat(form).doesNotContainPattern("name=\"token\"[^>]*value=");

        String list = body(post("/tokens/" + created.id()).param("name", "Rinominato").param("token", "").param("expiresAt", ""));
        assertThat(list).contains("Rinominato");
        assertThat(service.resolve(created.id(), "HUGGINGFACE")).isEqualTo(SECRET);
    }

    @Test
    void deleteRemovesTheTokenAndReturnsTheList() throws Exception {
        var created = service.create("HUGGINGFACE", "Personale", SECRET, null);

        String list = body(delete("/tokens/" + created.id()));

        assertThat(list).contains("Nessun token salvato");
        assertThat(repository.count()).isZero();
    }

    @Test
    void anExpiringTokenIsBadgedInTheList() throws Exception {
        service.create("CIVITAI", "Presto", SECRET, java.time.LocalDate.now().plusDays(3));

        String list = body(get("/tokens"));

        assertThat(list).contains("In scadenza il").contains("text-warning");
    }
}
