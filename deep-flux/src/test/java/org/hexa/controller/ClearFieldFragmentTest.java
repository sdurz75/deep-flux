package org.hexa.controller;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fragments/core/clear-field :: wrap}: con {@code clearable=true} avvolge l'input e aggiunge UN bottone {@code type="button"}; con
 * {@code false}/{@code null} esce solo l'input. Titolo di default da {@code field.clear}.
 */
@SpringBootTest
class ClearFieldFragmentTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    /** Host in {@code src/test/resources/templates/test/clear-field-host.html}: clearable/title arrivano dal contesto. */
    private String render(Boolean clearable, String title) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("clearable", clearable);
        context.setVariable("title", title);
        return templateEngine.process("test/clear-field-host", context);
    }

    @Test
    void clearableWrapsTheInputAndAddsOneButtonWithTheDefaultTitle() {
        String html = render(true, null);
        assertThat(html).contains("id=\"f\"").contains("x-show=\"filled\"").contains("title=\"Clear the field\"");
        assertThat(html.split("<button", -1)).hasSize(2);
        assertThat(html).contains("type=\"button\"");
    }

    @Test
    void explicitTitleWins() {
        assertThat(render(true, "Azzera")).contains("title=\"Azzera\"");
    }

    @Test
    void notClearableEmitsOnlyTheInput() {
        for (Boolean off : new Boolean[] {false, null}) {
            String html = render(off, null);
            assertThat(html).contains("id=\"f\"").doesNotContain("<button").doesNotContain("x-data");
        }
    }
}
