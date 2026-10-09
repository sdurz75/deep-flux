package org.dual.hexa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/API.md} e' l'indice delle API pubbliche (al posto del javadoc, che non si pubblica): ogni porta {@code port.in} delle librerie vi compare e
 * ogni link punta a un file che esiste. Senza contesto Spring; i percorsi sono relativi al modulo ({@code hexa-integration-tests}), la radice e' {@code ..}.
 */
class ApiIndexTest {

    private static final Path ROOT = Path.of("..");
    private static final List<String> LIBRARIES = List.of("hexa-core", "hexa-ai", "hexa-pwa", "hexa-oauth2");
    private static final Pattern LINK = Pattern.compile("- \\[`(\\w+)`\\]\\((\\.\\./[^)]+)\\)");

    @Test
    void everyLinkedSourceExists() throws IOException {
        List<String> missing = new ArrayList<>();
        Matcher m = LINK.matcher(Files.readString(ROOT.resolve("docs/API.md")));
        while (m.find()) {
            if (!Files.isRegularFile(ROOT.resolve("docs").resolve(m.group(2)))) {
                missing.add(m.group(2));
            }
        }
        assertThat(missing).as("link di docs/API.md verso file inesistenti").isEmpty();
    }

    @Test
    void everyPublicPortOfTheLibrariesIsListed() throws IOException {
        String index = Files.readString(ROOT.resolve("docs/API.md"));
        List<String> unlisted = new ArrayList<>();
        for (String library : LIBRARIES) {
            try (Stream<Path> files = Files.walk(ROOT.resolve(library).resolve("src/main/java"))) {
                files.filter(p -> p.toString().replace('\\', '/').contains("/port/in/") && p.toString().endsWith(".java")
                        && !p.getFileName().toString().equals("package-info.java"))
                        .forEach(p -> {
                            String link = "](../" + library + "/src/main/java/"
                                    + ROOT.resolve(library).resolve("src/main/java").relativize(p).toString().replace('\\', '/') + ")";
                            if (!index.contains(link)) {
                                unlisted.add(p.getFileName().toString());
                            }
                        });
            }
        }
        assertThat(unlisted).as("porte port.in mancanti in docs/API.md (rigenerare l'indice)").isEmpty();
    }
}
