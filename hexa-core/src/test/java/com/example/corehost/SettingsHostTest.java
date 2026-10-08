package com.example.corehost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.secrets.domain.SecretType;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.secrets.port.out.ISecretTypeCatalog;
import org.dual.hexa.core.web.LayoutSlotsAdvice;
import org.springframework.jdbc.core.JdbcTemplate;
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
        "app.secrets.expiry-check-enabled=false"})
@AutoConfigureMockMvc
@Import({SettingsHostTest.Config.class, SettingsHostTest.RichConfig.class})
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

    /** Un modulo con segreto, lista e righe: i segreti finiscono in /secrets (tipo `managed`), mai in module_config ne' nell'HTML. */
    @TestConfiguration
    static class RichConfig {
        @Bean
        IConfigModule richModule() {
            return new IConfigModule() {
                @Override
                public String id() {
                    return "rich";
                }

                @Override
                public String titleKey() {
                    return "rich.title";
                }

                @Override
                public List<ConfigField> fields() {
                    return List.of(ConfigField.secret("token", "RICH_TYPE", "rich.token", null),
                            ConfigField.list("allowed", "rich.allowed", null, null, null, null, true, true),
                            ConfigField.collection("items", "rich.items", null, List.of(ConfigField.Column.text("slug", "rich.slug", null, null, true),
                                    ConfigField.Column.secret("secret", "rich.secret")), "slug", 5, "RICH_TYPE"));
                }
            };
        }

        @Bean
        ISecretTypeCatalog richTypes() {
            return () -> List.of(new SecretType("RICH_TYPE", "secrets.type.GENERIC", true));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired ISecrets secrets;
    @Autowired JdbcTemplate jdbc;
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

    @Test
    void secretsListsAndRowsAreSavedRenderedWithoutSecretsAndTheSecretsAreManaged() throws Exception {
        try {
            String saved = mockMvc.perform(post("/settings/rich").header("HX-Request", "true").param("token", "tok-SECRET-1234")
                            .param("allowed", "A@x.it\nb@x.it").param("items.__new.slug", "google").param("items.__new.secret", "row-SECRET-9876"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(saved).doesNotContain("SECRET").contains("…1234", "…9876", "a@x.it", "items.google.__row");
            assertThat(settings.values("rich").getSecret("token")).contains("tok-SECRET-1234");
            assertThat(settings.values("rich").getRows("items")).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo("google");
                assertThat(row.secret("secret")).contains("row-SECRET-9876");
            });
            assertThat(jdbc.queryForObject("select count(*) from module_config where config_value like '%SECRET%'", Integer.class)).isZero();

            String page = mockMvc.perform(get("/secrets")).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("rich/token", "rich/items/google/secret").doesNotContain("SECRET-1234");
            long id = secrets.find("RICH_TYPE", "rich/token").orElseThrow().id();
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/secrets/" + id).header("HX-Request", "true"))
                    .andExpect(status().isUnprocessableEntity());

            mockMvc.perform(post("/settings/rich").header("HX-Request", "true").param("items.google.__row", "1").param("items.google.__remove", "true"))
                    .andExpect(status().isOk());
            assertThat(settings.values("rich").getRows("items")).isEmpty();
            assertThat(secrets.find("RICH_TYPE", "rich/items/google/secret")).isEmpty();
        } finally {
            mockMvc.perform(post("/settings/rich/reset"));
        }
        assertThat(secrets.find("RICH_TYPE", "rich/token")).isEmpty();
    }
}
