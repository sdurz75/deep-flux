package ${package}.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Le pagine rendono col layout del core: sidebar con le voci dell'app, selettore tema, breadcrumbs, fragment htmx. */
@SpringBootTest
@AutoConfigureMockMvc
class PagesRenderingTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void homeUsesTheSidebarLayoutWithThemeSwitch() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("<aside", "href=\"/example\"", "data-theme-value=\"dark\"", "${appName}");
    }

    @Test
    @Transactional
    void theExamplePageListsAndAddsThroughHtmx() throws Exception {
        String page = mockMvc.perform(get("/example")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("aria-label=\"Percorso\"", "id=\"example-list\"");

        String fragment = mockMvc.perform(post("/example").header("HX-Request", "true").param("title", "ciao"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fragment).contains("ciao").doesNotContain("<aside");
    }

    @Test
    @Transactional
    void aBlankTitleShowsAnInlineError() throws Exception {
        String fragment = mockMvc.perform(post("/example").header("HX-Request", "true").param("title", " "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(fragment).contains("obbligatorio");
    }

    /** Una chiave aggiunta a un bundle e dimenticata nell'altro. */
    @Test
    void messageBundlesHaveMatchingKeys() throws IOException {
        assertThat(load("/messages.properties").keySet()).containsExactlyInAnyOrderElementsOf(load("/messages_en.properties").keySet());
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PagesRenderingTests.class.getResourceAsStream(resource)) {
            properties.load(in);
        }
        return properties;
    }
}
