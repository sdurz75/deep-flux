package org.dual.hexa.controller;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fragments/core/fullscreen-modal :: screen}: schermata che copre tutta la finestra, autosufficiente (nessuna variabile Alpine dal chiamante),
 * senza modo di chiuderla e col corpo del chiamante dentro.
 */
@SpringBootTest
class FullscreenModalFragmentTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    @Test
    void coversTheWholeWindowAndTrapsFocus() {
        String html = templateEngine.process("test/fullscreen-modal-host", new Context(Locale.ENGLISH));
        assertThat(html).contains("fixed inset-0").contains("x-trap.inert.noscroll").contains("role=\"dialog\"").contains("aria-modal=\"true\"")
                .contains("aria-labelledby=\"t\"").contains("contenuto");
    }

    @Test
    void cannotBeDismissedAndWorksWithoutJavaScript() {
        String html = templateEngine.process("test/fullscreen-modal-host", new Context(Locale.ENGLISH));
        // niente scrim cliccabile, niente chiusura, niente x-cloak/x-show: senza Alpine la schermata (e il form dentro) resta raggiungibile
        assertThat(html).doesNotContain("@click").doesNotContain("@keydown").doesNotContain("x-cloak").doesNotContain("x-show");
    }
}
