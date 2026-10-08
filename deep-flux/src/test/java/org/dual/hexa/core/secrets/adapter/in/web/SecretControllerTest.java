package org.dual.hexa.core.secrets.adapter.in.web;

import org.dual.hexa.core.events.port.out.ISystemEventStore;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.secrets.port.out.ISecretStore;
import java.util.List;

import org.dual.hexa.core.secrets.domain.SecretType;

import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD /secrets: pagina, dialog, validazione col retarget del form, e il token in chiaro non compare in NESSUNA risposta. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecretControllerTest.Types.class)
class SecretControllerTest {

    /** Il catalogo dei tipi e' un punto di estensione: si sommano; qui se ne aggiunge uno `managed`, come farebbe un modulo. */
    @TestConfiguration
    static class Types {
        @Bean
        ISecretTypeCatalog testTypes() {
            return () -> List.of(new SecretType("HUGGINGFACE", "secrets.type.HUGGINGFACE"), new SecretType("CIVITAI", "secrets.type.CIVITAI"),
                    new SecretType("MODULE_OWNED", "secrets.type.GENERIC", true));
        }
    }

    private static final String SECRET = "hf_super_secret_value_ABCD";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ISecretStore repository;
    @Autowired
    private ISystemEventStore eventRepository;
    @Autowired
    private ISecrets service;

    /** I test condividono il DB: i token e gli eventi di questa classe non devono restare per le altre (es. quelle che contano i token HuggingFace). */
    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        eventRepository.deleteAll();
    }

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
    void pageRendersTheEmptyListAndTheDialog() throws Exception {
        String page = mockMvc.perform(get("/secrets")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Nessun segreto salvato").contains("id=\"secret-form\"").contains("id=\"secret-list\"")
                .contains("hx-get=\"/secrets/new\"");
        assertThat(page).doesNotContain("Chiave di cifratura mancante"); // la chiave di test e' configurata (pom.xml)
    }

    @Test
    void newFormHasTypeSelectNameValuePasswordAndDateButNoValues() throws Exception {
        String form = body(get("/secrets/new"));

        assertThat(form).contains("name=\"type\"", "name=\"name\"", "name=\"expiresAt\"", "type=\"date\"")
                .containsPattern("<input type=\"password\"[^>]*name=\"value\"[^>]*required");
        assertThat(form).contains("x-data=\"pinesSelect\"");
    }

    @Test
    void createValidatesWithRetargetAndSavesWithTheSavedTriggerAndAnUpdatedList() throws Exception {
        var invalid = mockMvc.perform(post("/secrets").header("HX-Request", "true").param("type", "HUGGINGFACE").param("name", " ")
                        .param("value", SECRET)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(invalid.getHeader("HX-Retarget")).isEqualTo("#secret-form");
        assertThat(invalid.getHeader("HX-Reswap")).isEqualTo("outerHTML");
        assertThat(invalid.getHeader("HX-Trigger")).isNull();
        assertThat(invalid.getContentAsString()).contains("Il nome").doesNotContain(SECRET);
        assertThat(repository.count()).isZero();

        var ok = mockMvc.perform(post("/secrets").header("HX-Request", "true").param("type", "HUGGINGFACE").param("name", "Personale")
                        .param("value", SECRET).param("expiresAt", "")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ok.getHeader("HX-Trigger")).contains("secret-saved");
        assertThat(ok.getHeader("HX-Retarget")).isNull();
        assertThat(ok.getContentAsString()).contains("Personale").contains("…ABCD").containsIgnoringCase("huggingface").contains("Nessuna scadenza")
                .doesNotContain(SECRET);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void duplicateNameAndInvalidDateAreFormErrors() throws Exception {
        service.create("HUGGINGFACE", "Personale", SECRET, null);

        var duplicate = mockMvc.perform(post("/secrets").header("HX-Request", "true").param("type", "HUGGINGFACE")
                .param("name", "personale").param("value", "x")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(duplicate.getHeader("HX-Retarget")).isEqualTo("#secret-form");
        assertThat(duplicate.getContentAsString()).contains("Esiste gia").contains("chiamato &quot;personale&quot;");

        var badDate = mockMvc.perform(post("/secrets").header("HX-Request", "true").param("type", "CIVITAI")
                .param("name", "Altro").param("value", "x").param("expiresAt", "31/12/2026")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(badDate.getHeader("HX-Retarget")).isEqualTo("#secret-form");
        assertThat(badDate.getContentAsString()).contains("data valida");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void editFormIsPrefilledWithoutTheTokenAndUpdateKeepsItWhenLeftBlank() throws Exception {
        var created = service.create("HUGGINGFACE", "Personale", SECRET, java.time.LocalDate.now().plusDays(200));

        String form = body(get("/secrets/" + created.id() + "/edit"));
        assertThat(form).contains("value=\"Personale\"").contains("value=\"" + created.expiresAt() + "\"").doesNotContain("name=\"type\"");
        assertThat(form).doesNotContainPattern("name=\"value\"[^>]*value=");

        String list = body(post("/secrets/" + created.id()).param("name", "Rinominato").param("value", "").param("expiresAt", ""));
        assertThat(list).contains("Rinominato");
        assertThat(service.resolve(created.id(), "HUGGINGFACE")).isEqualTo(SECRET);
    }

    @Test
    void deleteRemovesTheTokenAndReturnsTheList() throws Exception {
        var created = service.create("HUGGINGFACE", "Personale", SECRET, null);

        String list = body(delete("/secrets/" + created.id()));

        assertThat(list).contains("Nessun segreto salvato");
        assertThat(repository.count()).isZero();
    }

    @Test
    void theOldTokensAddressRedirectsPermanentlyToSecrets() throws Exception {
        mockMvc.perform(get("/tokens")).andExpect(status().isMovedPermanently())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/secrets"));
    }

    /** Un segreto `managed` (di un modulo) si vede in elenco col suo badge, senza bottoni; il tipo non e' fra quelli creabili e le scritture sono rifiutate. */
    @Test
    void aManagedSecretIsListedReadOnlyAndItsTypeIsNotCreatable() throws Exception {
        var owned = service.store("MODULE_OWNED", "mod/chiave", SECRET);

        String list = body(get("/secrets"));
        assertThat(list).contains("mod/chiave").contains("gestito da un modulo").doesNotContain("/secrets/" + owned.id() + "/edit")
                .doesNotContain("hx-delete=\"/secrets/" + owned.id());
        assertThat(body(get("/secrets/new"))).doesNotContain("MODULE_OWNED");

        var rejected = mockMvc.perform(delete("/secrets/" + owned.id()).header("HX-Request", "true")).andReturn().getResponse();
        assertThat(rejected.getStatus()).isEqualTo(422);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void anExpiringTokenIsBadgedInTheList() throws Exception {
        service.create("CIVITAI", "Presto", SECRET, java.time.LocalDate.now().plusDays(3));

        String list = body(get("/secrets"));

        assertThat(list).contains("In scadenza il").contains("text-warning");
    }
}
