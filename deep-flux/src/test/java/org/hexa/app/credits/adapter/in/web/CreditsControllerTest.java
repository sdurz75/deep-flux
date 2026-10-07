package org.hexa.app.credits.adapter.in.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Barra dei crediti col database reale; nei test non c'e' la management key, quindi OpenRouter non compare e non si chiama nulla. */
@SpringBootTest
@AutoConfigureMockMvc
class CreditsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from replicate_balance_anchor");
    }

    @Test
    void barAsksForTheReplicateBalanceUntilOneIsSet() throws Exception {
        String body = mockMvc.perform(get("/credits/bar").header("HX-Request", "true").header("Accept-Language", "it")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Imposta saldo Replicate").doesNotContain("OpenRouter");
    }

    @Test
    void settingTheBalanceShowsTheEstimateAndClosesThePopover() throws Exception {
        var result = mockMvc.perform(post("/credits/replicate").param("balance", "25,50").header("HX-Request", "true").header("Accept-Language", "it"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Trigger", org.hamcrest.Matchers.containsString("credits-saved")))
                .andReturn().getResponse().getContentAsString();

        assertThat(result).contains("Replicate ~$25,50").contains("type=\"button\"");
    }

    @Test
    void anInvalidBalanceIsAToastNotAnError() throws Exception {
        var response = mockMvc.perform(post("/credits/replicate").param("balance", "abc").header("HX-Request", "true").header("Accept-Language", "it"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(response.getHeader("HX-Trigger")).contains("system-toast").contains("WARNING").doesNotContain("credits-saved");
        assertThat(response.getContentAsString()).contains("Imposta saldo Replicate");
    }
}
