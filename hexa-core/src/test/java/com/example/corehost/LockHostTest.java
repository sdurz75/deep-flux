package com.example.corehost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dual.hexa.core.lock.port.in.ILock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Il cancello del blocco con PIN in un host fuori da {@code org.dual.hexa} (solo hexa-core: il blocco e' del core, non della PWA): senza PIN non cambia nulla; con il PIN nulla passa (pagine, htmx, SSE, immagini)
 * finche' la sessione non e' sbloccata, e una sessione inattiva si richiude da sola.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.tokens.expiry-check-enabled=false"})
@AutoConfigureMockMvc
class LockHostTest {

    private static final String PIN = "2468";

    @Autowired MockMvc mockMvc;
    @Autowired ILock lock;

    @AfterEach
    void turnOff() {
        lock.reset();
    }

    private MockHttpSession unlockedSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var result = mockMvc.perform(post("/unlock").session(session).param("pin", PIN).param("next", "/")).andExpect(status().isSeeOther()).andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    @Test
    void withoutAPinNothingIsGated() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/security")).andExpect(status().isOk());
    }

    @Test
    void aLockedSessionIsSentToTheUnlockPageWithItsDestination() throws Exception {
        lock.enable(PIN, 300);

        MockHttpServletResponse navigation = mockMvc.perform(get("/security?x=1").accept("text/html")).andExpect(status().isFound()).andReturn().getResponse();
        assertThat(navigation.getRedirectedUrl()).isEqualTo("/unlock?next=%2Fsecurity%3Fx%3D1");
        assertThat(navigation.getHeader("Cache-Control")).contains("no-store");

        MockHttpServletResponse htmx = mockMvc.perform(get("/security").header("HX-Request", "true").header("HX-Current-URL", "http://localhost/security"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertThat(htmx.getHeader("HX-Redirect")).isEqualTo("/unlock?next=%2Fsecurity");
    }

    @Test
    void imagesEventsAndFetchesAreRefusedWithoutARedirect() throws Exception {
        lock.enable(PIN, 300);

        mockMvc.perform(get("/images/abcd.png")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/lock/touch")).andExpect(status().isUnauthorized());
    }

    @Test
    void htmxOnTheUnlockPageDoesNotRedirectInALoop() throws Exception {
        lock.enable(PIN, 300);

        MockHttpServletResponse response = mockMvc.perform(get("/credits/bar").header("HX-Request", "true").header("HX-Current-URL", "http://localhost/unlock?next=%2F"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();

        assertThat(response.getHeader("HX-Redirect")).isNull();
    }

    @Test
    void onlyTheUnlockPageStaysReachableWhileLocked() throws Exception {
        lock.enable(PIN, 300);

        String unlock = mockMvc.perform(get("/unlock")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unlock).contains("name=\"pin\"").doesNotContain("id=\"lock-idle\"");
    }

    @Test
    void aWrongPinShowsAMessageAndTheRightOneUnlocksTheSession() throws Exception {
        lock.enable(PIN, 300);

        String wrong = mockMvc.perform(post("/unlock").param("pin", "0000").param("next", "/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(wrong).contains("PIN errato");

        MockHttpSession session = unlockedSession();
        String page = mockMvc.perform(get("/").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"lock-idle\"", "data-seconds=\"300\"", "href=\"/security\"");
        mockMvc.perform(post("/lock/touch").session(session)).andExpect(status().isNoContent());
    }

    @Test
    void theDestinationAfterUnlockIsAlwaysALocalPath() throws Exception {
        lock.enable(PIN, 300);

        for (String hostile : new String[] {"//evil.example/x", "https://evil.example", "/\\evil.example"}) {
            String location = mockMvc.perform(post("/unlock").param("pin", PIN).param("next", hostile)).andExpect(status().isSeeOther())
                    .andReturn().getResponse().getHeader("Location");
            assertThat(location).as(hostile).isEqualTo("/");
        }
    }

    @Test
    void anIdleSessionLocksItselfEvenIfTheClientDidNothing() throws Exception {
        lock.enable(PIN, 60);
        MockHttpSession session = unlockedSession();
        mockMvc.perform(get("/").session(session)).andExpect(status().isOk());

        session.setAttribute("hexa.lock.lastInput", System.currentTimeMillis() - 200_000L);

        mockMvc.perform(get("/").session(session).accept("text/html")).andExpect(status().isFound());
    }

    @Test
    void lockNowClosesTheSession() throws Exception {
        lock.enable(PIN, 300);
        MockHttpSession session = unlockedSession();

        mockMvc.perform(post("/lock/now").session(session).accept("text/html")).andExpect(status().isSeeOther());

        mockMvc.perform(get("/").session(session).accept("text/html")).andExpect(status().isFound());
    }

    @Test
    void thePinCanBeChangedAndRemovedOnlyWithTheCurrentOne() throws Exception {
        lock.enable(PIN, 300);
        MockHttpSession session = unlockedSession();

        String refused = mockMvc.perform(post("/security/disable").session(session).header("HX-Request", "true").param("current", "9999"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("PIN errato");
        assertThat(lock.isEnabled()).isTrue();

        String mismatch = mockMvc.perform(post("/security/pin/change").session(session).header("HX-Request", "true")
                .param("current", PIN).param("pin", "1357").param("confirm", "1358")).andReturn().getResponse().getContentAsString();
        assertThat(mismatch).contains("non coincidono");

        mockMvc.perform(post("/security/disable").session(session).header("HX-Request", "true").param("current", PIN)).andExpect(status().isOk());
        assertThat(lock.isEnabled()).isFalse();
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void thePinCanBeSetFromTheSecurityPage() throws Exception {
        String saved = mockMvc.perform(post("/security/pin").header("HX-Request", "true").param("pin", PIN).param("confirm", PIN).param("timeout", "900"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(saved).contains("Modifica salvata", "15 minuti");
        assertThat(lock.isEnabled()).isTrue();
        assertThat(lock.idleTimeoutSeconds()).isEqualTo(900);
    }
}
