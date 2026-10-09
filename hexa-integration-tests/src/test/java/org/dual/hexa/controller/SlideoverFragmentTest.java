package org.dual.hexa.controller;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fragments/core/slideover :: drawer}: scrim + aside col corpo del chiamante; {@code persistent} decide se l'aside esiste anche da md in su
 * (colonna fissa) o solo sotto md.
 */
@SpringBootTest
class SlideoverFragmentTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    private String render(boolean persistent) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("persistent", persistent);
        return templateEngine.process("test/slideover-host", context);
    }

    @Test
    void persistentKeepsAFixedColumnFromMd() {
        String html = render(true);
        assertThat(html).contains("<aside").contains("md:translate-x-0").contains("contenuto").contains("x-show=\"navOpen\"").doesNotContain("md:hidden\" :class");
        assertThat(html.split("<aside", -1)).hasSize(2);
    }

    @Test
    void nonPersistentExistsOnlyBelowMd() {
        String html = render(false);
        assertThat(html).contains("<aside").contains("contenuto").doesNotContain("md:translate-x-0");
        assertThat(html.substring(html.indexOf("<aside"), html.indexOf(">", html.indexOf("<aside")))).contains("md:hidden");
    }
}
