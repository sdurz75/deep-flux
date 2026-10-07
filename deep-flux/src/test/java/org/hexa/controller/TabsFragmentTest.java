package org.hexa.controller;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fragments/core/tabs}: la barra e' un {@code tablist} con una scheda per elemento, la selezionata ha {@code aria-selected=true}; con
 * {@code hxTarget} le schede portano {@code hx-get}, senza restano link normali.
 */
@SpringBootTest
class TabsFragmentTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    private String render(String target) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("target", target);
        return templateEngine.process("test/tabs-host", context);
    }

    @Test
    void rendersOneLinkPerTabAndMarksTheSelectedOne() {
        String html = render(null);
        assertThat(html).contains("role=\"tablist\"").contains("href=\"/x?tab=a\"").contains("href=\"/x?tab=b\"");
        assertThat(html.split("role=\"tab\"", -1)).hasSize(3);
        assertThat(html).contains("aria-selected=\"true\"").contains("aria-selected=\"false\"").doesNotContain("hx-get");
    }

    @Test
    void htmxTargetAddsHxGetAndSwap() {
        String html = render("#box");
        assertThat(html).contains("hx-get=\"/x?tab=a\"").contains("hx-target=\"#box\"").contains("hx-swap=\"innerHTML\"");
    }
}
