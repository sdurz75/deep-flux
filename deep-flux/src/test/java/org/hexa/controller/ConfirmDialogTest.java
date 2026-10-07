package org.hexa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Le conferme passano dal dialog Pines del core ({@code fragments/core/confirm-dialog}), mai dal {@code confirm()} nativo: il layout lo include UNA volta
 * (ascolta {@code htmx:confirm} ed espone {@code window.hexaConfirm}) e nessun template usa il confirm del browser.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmDialogTest {

    private static final List<Path> TEMPLATE_ROOTS = List.of(Path.of("../hexa-core/src/main/resources/templates"),
            Path.of("../hexa-ai/src/main/resources/templates"), Path.of("src/main/resources/templates"));
    /** Il confirm() nativo o window.confirm(): non {@code hexaConfirm} ne' le chiavi i18n {@code ....confirm(...)}. */
    private static final Pattern NATIVE_CONFIRM = Pattern.compile("window\\.confirm\\s*\\(|(?<![\\w$.#{'])confirm\\s*\\(");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void layoutIncludesTheConfirmDialogOnce() throws Exception {
        String body = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body.split("id=\"confirm-dialog\"", -1)).hasSize(2);
        assertThat(body).contains("@htmx:confirm.window=\"onHtmxConfirm($event)\"", "window.hexaConfirm", "role=\"dialog\"");
    }

    @Test
    void noTemplateUsesTheNativeBrowserConfirm() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : TEMPLATE_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                    if (NATIVE_CONFIRM.matcher(content).find()) {
                        found.add(root.relativize(file).toString());
                    }
                }
            }
        }
        assertThat(found).as("template che usano confirm() nativo: usare hx-confirm o window.hexaConfirm").isEmpty();
    }
}
