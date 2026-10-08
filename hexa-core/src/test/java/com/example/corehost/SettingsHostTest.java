package com.example.corehost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.web.LayoutSlotsAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Un modulo di un host fuori da {@code org.dual.hexa} si autoregistra: pagina {@code /settings}, voce di menu, salvataggio e rifiuto di un valore non valido. */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.tokens.expiry-check-enabled=false"})
@AutoConfigureMockMvc
@Import(SettingsHostTest.Config.class)
class SettingsHostTest {

    @TestConfiguration
    static class Config {
        @Bean
        IConfigModule demoModule() {
            return new IConfigModule() {
                @Override
                public String id() {
                    return "demo";
                }

                @Override
                public String titleKey() {
                    return "demo.title";
                }

                @Override
                public List<ConfigField> fields() {
                    return List.of(ConfigField.integer("limit", 5, 1, 10, "demo.limit", null), ConfigField.bool("flag", true, "demo.flag", null),
                            ConfigField.select("mode", "a", List.of("a", "b"), "demo.mode", null), ConfigField.text("name", "x", "demo.name", null));
                }
            };
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired IModuleSettings settings;
    @Autowired LayoutSlotsAdvice layoutSlots;

    @Test
    void aRegisteredModuleGetsASectionAndAMenuEntry() throws Exception {
        String body = mockMvc.perform(get("/settings")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("id=\"settings-demo\"", "name=\"limit\"", "name=\"flag\"", "name=\"mode\"", "name=\"name\"");
        assertThat(layoutSlots.manageMenuEntries()).anyMatch(entry -> entry.path().equals("/settings"));
    }

    @Test
    void savingChangesTheEffectiveValuesAndAnInvalidOneIsRejectedInThePanel() throws Exception {
        try {
            mockMvc.perform(post("/settings/demo").param("limit", "7").param("mode", "b").param("name", "y").header("HX-Request", "true"))
                    .andExpect(status().isOk());
            assertThat(settings.values("demo").getInt("limit")).isEqualTo(7);
            assertThat(settings.values("demo").getBoolean("flag")).isFalse();
            assertThat(settings.values("demo").getString("mode")).isEqualTo("b");

            String body = mockMvc.perform(post("/settings/demo").param("limit", "99").header("HX-Request", "true"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("value=\"99\"");
            assertThat(settings.values("demo").getInt("limit")).isEqualTo(7);
        } finally {
            mockMvc.perform(post("/settings/demo/reset"));
        }
        assertThat(settings.values("demo").getInt("limit")).isEqualTo(5);
    }

    @Test
    void anUnknownModuleIsNotFound() throws Exception {
        mockMvc.perform(post("/settings/nope")).andExpect(status().isNotFound());
    }
}
