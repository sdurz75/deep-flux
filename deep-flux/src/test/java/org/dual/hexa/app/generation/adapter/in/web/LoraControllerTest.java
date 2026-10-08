package org.dual.hexa.app.generation.adapter.in.web;

import org.dual.hexa.app.generation.port.out.ILoraPresetStore;
import org.dual.hexa.app.generation.application.LoraPresetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD /loras: pagina, dialog, validazione col retarget del form, cancellazione. */
@SpringBootTest
@AutoConfigureMockMvc
class LoraControllerTest {

    @MockitoBean
    private org.dual.hexa.app.generation.port.out.IPredictionGateway predictionGateway;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ILoraPresetStore repository;
    @Autowired
    private LoraPresetService service;
    @Autowired
    private org.dual.hexa.core.secrets.port.in.ISecrets secrets;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("HX-Request", "true")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void pageRendersTheEmptyListTheDialogAndTheNavLink() throws Exception {
        String page = mockMvc.perform(get("/loras")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(page).contains("Nessun LoRA salvato").contains("id=\"lora-form\"").contains("id=\"lora-list\"")
                .contains("hx-get=\"/loras/new\"").contains("href=\"/loras\"");
    }

    @Test
    void newFormHasAllFieldsWithTheDefaultScale() throws Exception {
        String form = body(get("/loras/new"));

        assertThat(form).contains("name=\"name\"", "name=\"source\"", "name=\"scale\"", "name=\"triggerWords\"", "name=\"note\"")
                .containsPattern("name=\"scale\"[^>]*value=\"1(\\.0)?\"");
    }

    @Test
    void createValidatesWithRetargetAndSavesWithTheSavedTrigger() throws Exception {
        var invalid = mockMvc.perform(post("/loras").header("HX-Request", "true").param("name", "Stile")
                .param("source", " ")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(invalid.getHeader("HX-Retarget")).isEqualTo("#lora-form");
        assertThat(invalid.getHeader("HX-Reswap")).isEqualTo("outerHTML");
        assertThat(invalid.getHeader("HX-Trigger")).isNull();
        assertThat(invalid.getContentAsString()).contains("La sorgente").contains("value=\"Stile\"");
        assertThat(repository.count()).isZero();

        var ok = mockMvc.perform(post("/loras").header("HX-Request", "true").param("name", "Stile").param("source", "owner/stile")
                .param("scale", "0.8").param("triggerWords", "stl style").param("note", "per ritratti")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(ok.getHeader("HX-Trigger")).contains("lora-saved");
        assertThat(ok.getHeader("HX-Retarget")).isNull();
        assertThat(ok.getContentAsString()).contains("Stile", "owner/stile", "stl style", "per ritratti", "0,8");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void duplicateNameOutOfRangeAndInvalidScaleAreFormErrors() throws Exception {
        service.create("Stile", "owner/stile", 1.0, null, null);

        var duplicate = mockMvc.perform(post("/loras").header("HX-Request", "true").param("name", "stile").param("source", "x"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(duplicate.getHeader("HX-Retarget")).isEqualTo("#lora-form");
        assertThat(duplicate.getContentAsString()).contains("Esiste gia");

        var range = mockMvc.perform(post("/loras").header("HX-Request", "true").param("name", "Altro").param("source", "x").param("scale", "5"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(range.getHeader("HX-Retarget")).isEqualTo("#lora-form");
        assertThat(range.getContentAsString()).contains("deve essere fra");

        var invalid = mockMvc.perform(post("/loras").header("HX-Request", "true").param("name", "Altro").param("source", "x").param("scale", "abc"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(invalid.getHeader("HX-Retarget")).isEqualTo("#lora-form");
        assertThat(invalid.getContentAsString()).contains("numero valido");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void editFormIsPrefilledAndUpdateChangesTheFields() throws Exception {
        var created = service.create("Stile", "owner/stile", 0.7, "stl", "nota");

        String form = body(get("/loras/" + created.id() + "/edit"));
        assertThat(form).contains("value=\"Stile\"", "value=\"owner/stile\"", "value=\"stl\"", "value=\"nota\"")
                .containsPattern("name=\"scale\"[^>]*value=\"0\\.7\"");

        String list = body(post("/loras/" + created.id()).param("name", "Rinominato").param("source", "owner/nuovo").param("scale", "1.2")
                .param("triggerWords", "").param("note", ""));
        assertThat(list).contains("Rinominato", "owner/nuovo");
        var updated = service.get(created.id());
        assertThat(updated.source()).isEqualTo("owner/nuovo");
        assertThat(updated.scale()).isEqualTo(1.2);
        assertThat(updated.triggerWords()).isNull();
    }

    @Test
    void deleteRemovesTheLoraAndReturnsTheList() throws Exception {
        var created = service.create("Stile", "owner/stile", 1.0, null, null);

        String list = body(delete("/loras/" + created.id()));

        assertThat(list).contains("Nessun LoRA salvato");
        assertThat(repository.count()).isZero();
    }

    @Test
    void theDefaultTokenIsChosenInTheFormAndShownMaskedInTheList() throws Exception {
        var token = secrets.create("HUGGINGFACE", "hf-ctrl-test", "hf_topsecret9876", null);
        try {
            assertThat(body(get("/loras/new"))).contains("name=\"secretId\"", "HuggingFace: hf-ctrl-test ••••9876");

            String list = body(post("/loras").param("name", "Privato").param("source", "https://huggingface.co/sdurz/privato")
                    .param("secretId", String.valueOf(token.id())));
            assertThat(list).contains("hf-ctrl-test ••••9876").doesNotContain("topsecret");

            var invalid = mockMvc.perform(post("/loras").header("HX-Request", "true").param("name", "Altro").param("source", "x")
                    .param("secretId", "abc")).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(invalid.getHeader("HX-Retarget")).isEqualTo("#lora-form");
            assertThat(invalid.getContentAsString()).contains("Il token scelto non esiste");
        } finally {
            repository.deleteAll();
            secrets.delete(token.id());
        }
    }
}
