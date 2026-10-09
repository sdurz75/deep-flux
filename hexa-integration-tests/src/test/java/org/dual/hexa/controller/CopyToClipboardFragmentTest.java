package org.dual.hexa.controller;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fragments/core/copy-to-clipboard :: copy}: il testo viaggia in {@code data-copy} (escapato, mai in un binding Alpine), le etichette
 * sono quelle passate e {@code extraClass} e' facoltativa.
 */
@SpringBootTest
class CopyToClipboardFragmentTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    private String render(String text, String extra) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("text", text);
        context.setVariable("extra", extra);
        return templateEngine.process("test/copy-host", context);
    }

    @Test
    void carriesTheTextInADataAttributeAndShowsBothLabels() {
        String html = render("a \"quoted\" <b>prompt</b>", null);
        assertThat(html).contains("type=\"button\"").contains("x-data=").contains("data-copy=\"a &quot;quoted&quot; &lt;b&gt;prompt&lt;/b&gt;\"")
                .contains(">Copia<").contains(">Copiato<").doesNotContain("<b>prompt");
    }

    @Test
    void extraClassIsAppended() {
        assertThat(render("x", "mt-1")).contains("mt-1");
    }
}
