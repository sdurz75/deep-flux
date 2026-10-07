package com.example.pwahost;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.dual.hexa.core.lock.port.in.ILock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** hexa-pwa dichiara al blocco del core (ILockExemptPaths) la sua shell: con il PIN attivo il browser puo' ancora installare e aggiornare l'app. */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.tokens.expiry-check-enabled=false"})
@AutoConfigureMockMvc
class PwaLockExemptionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ILock lock;

    @AfterEach
    void turnOff() {
        lock.reset();
    }

    @Test
    void theShellStaysReachableWhileTheAppIsLocked() throws Exception {
        lock.enable("2468", 300);

        mockMvc.perform(get("/sw.js")).andExpect(status().isOk());
        mockMvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk());
        mockMvc.perform(get("/offline")).andExpect(status().isOk());
        mockMvc.perform(get("/pwa/icons/icon.svg")).andExpect(status().isOk());
        mockMvc.perform(get("/").accept("text/html")).andExpect(status().isFound());
    }
}
