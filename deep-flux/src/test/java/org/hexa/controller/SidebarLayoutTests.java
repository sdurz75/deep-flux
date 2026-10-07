package org.hexa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Modalita' `app.layout.nav=sidebar` del layout del core: colonna laterale al posto dell'header, tema scelto una volta sola. */
@SpringBootTest(properties = "app.layout.nav=sidebar")
@AutoConfigureMockMvc
class SidebarLayoutTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sidebarReplacesTheTopHeaderAndKeepsTheThemeSwitchOnce() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("<aside", "md:translate-x-0", "id=\"notification-bell\"", "md:ml-64", "md:left-64");
        assertThat(body).doesNotContain("sticky top-0 z-40 border-b");
        // il selettore tema c'e' una volta sola (nella sidebar, non anche nella barra di stato)
        assertThat(body.split("data-theme-value=\"dark\"", -1)).hasSize(2);
        // le voci dell'host sono nella colonna
        assertThat(body).contains("href=\"/deep-chat\"");
    }
}
