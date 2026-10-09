package org.dual.hexa.controller;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code fragments/core/switch :: toggle}: resta una checkbox nativa (name/value inviati come prima), {@code checked}, {@code model} e
 * {@code title} sono facoltativi e spariscono se nulli.
 */
@SpringBootTest
class SwitchFragmentTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    private String render(boolean checked, String model, String title) {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("checked", checked);
        context.setVariable("model", model);
        context.setVariable("title", title);
        return templateEngine.process("test/switch-host", context);
    }

    @Test
    void isANativeCheckboxWithLabelAndNoOptionalAttributes() {
        String html = render(false, null, null);
        assertThat(html).contains("type=\"checkbox\"").contains("id=\"sw\"").contains("name=\"flag\"").contains("value=\"true\"")
                .contains("for=\"sw\"").contains("Etichetta").doesNotContain("checked=").doesNotContain("x-model").doesNotContain("title=");
    }

    @Test
    void checkedModelAndTitleAreRendered() {
        String html = render(true, "hf", "Aiuto");
        assertThat(html).contains("checked=\"checked\"").contains("x-model=\"hf\"").contains("title=\"Aiuto\"");
    }
}
