package org.hexa.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Stratificazione dei template, come {@code ArchitectureTest} lato Java: {@code app -> core}. Il core ({@code fragments/core}, {@code templates/core}) non conosce l'app, salvo i due punti di estensione che l'app
 * riempie. Una violazione si corregge nel template (parametri al posto del riferimento), non allentando la regola.
 */
class TemplateLayeringTest {

    private static final Path TEMPLATES = Path.of("../hexa-core/src/main/resources/templates"); // i template del core vivono nel modulo hexa-core
    private static final Pattern REFERENCE = Pattern.compile("fragments/(core|app)/([a-z0-9-]+)");
    /** Punti di estensione dell'app sul core (stesso elenco chiuso di {@code ArchitectureTest.CORE_EXTENSION_POINTS}, lato template). */
    private static final Set<String> APP_EXTENSION_POINTS = Set.of("fragments/app/nav", "fragments/app/status-extras");

    @Test
    void coreTemplatesDoNotDependOnApp() throws IOException {
        List<String> violations = new ArrayList<>(violations(TEMPLATES.resolve("fragments/core"), Set.of("app"), APP_EXTENSION_POINTS));
        violations.addAll(violations(TEMPLATES.resolve("core"), Set.of("app"), APP_EXTENSION_POINTS));
        assertThat(violations).as("template core che referenziano l'app (fuori dai punti di estensione)").isEmpty();
    }

    /**
     * Riferimenti vietati nel markup; le menzioni nei commenti HTML sono ammesse (documentano il contratto) e si tolgono prima di cercare.
     */
    private static List<String> violations(Path root, Set<String> forbidden, Set<String> allowed) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                Matcher m = REFERENCE.matcher(content);
                while (m.find()) {
                    String reference = "fragments/" + m.group(1) + "/" + m.group(2);
                    if (forbidden.contains(m.group(1)) && !allowed.contains(reference)) {
                        found.add(root.relativize(file) + " -> " + reference);
                    }
                }
            }
        }
        return found;
    }
}
