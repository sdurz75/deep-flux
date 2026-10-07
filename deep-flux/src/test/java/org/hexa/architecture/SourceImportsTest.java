package org.hexa.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le stesse regole di layering di {@link ArchitectureTest}, applicate al SORGENTE invece che al bytecode. ArchUnit non vede gli
 * import usati solo in Javadoc (javac li scarta), ma restano una dipendenza dichiarata: un {@code import} di un adapter in un
 * domain, o di una classe di {@code chat} in {@code generation}, e' un'inversione di strati anche se oggi non compila in niente.
 * Vale per ogni riferimento a un tipo dell'app o del core nel testo del file (import e nomi qualificati nel codice o nei commenti).
 * <p>
 * Regole: il core non conosce l'app; fra sottosistemi si usano solo {@code port.in} e {@code domain} (kernel e {@code core.web}
 * esclusi; l'app implementa solo {@link ArchitectureTest#CORE_EXTENSION_POINTS}); {@code domain} dipende solo da {@code domain};
 * {@code port} solo da {@code domain} e {@code port}; {@code application} mai da un adapter; {@code adapter.in} e {@code adapter.out} mai l'uno dall'altro e {@code adapter.in} mai una {@code port.out}.
 */
class SourceImportsTest {

    private static final String ROOT = "org.hexa";
    private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern REFERENCE = Pattern.compile("\\borg\\.hexa\\.(?:core|app)\\.[\\w.]*");
    private static final Pattern SLICE = Pattern.compile("^" + Pattern.quote(ROOT) + "\\.(core|app)\\.([^.]+)(?:\\.(.*))?$");

    @Test
    void sourcesRespectTheLayeringEvenInJavadocAndImports() throws IOException {
        List<String> violations = new ArrayList<>();
        // I sorgenti del core stanno in hexa-core e hexa-ai, quelli dell'app qui.
        List<Path> sources = new ArrayList<>();
        for (String root : List.of("../hexa-core/src/main/java", "../hexa-ai/src/main/java", "src/main/java")) {
            try (Stream<Path> walk = Files.walk(Path.of(root))) {
                sources.addAll(walk.filter(f -> f.toString().endsWith(".java")).toList());
            }
        }
        {
            for (Path file : sources) {
                String source = Files.readString(file);
                Matcher pkg = PACKAGE.matcher(source);
                if (!pkg.find()) {
                    continue;
                }
                Matcher from = SLICE.matcher(pkg.group(1));
                if (!from.matches() || from.group(2).equals("autoconfigure") || pkg.group(1).contains(".autoconfigure")) {
                    continue; // wiring Spring Boot delle librerie: non e' un sottosistema
                }
                Matcher reference = REFERENCE.matcher(source);
                while (reference.find()) {
                    String type = typeOf(reference.group());
                    Matcher to = SLICE.matcher(packageOf(reference.group()));
                    if (to.matches()) {
                        String problem = check(from, to, type);
                        if (problem != null) {
                            violations.add(file.getFileName() + " -> " + type + ": " + problem);
                        }
                    }
                }
            }
        }
        assertThat(violations).as("riferimenti che violano il layering (anche solo in Javadoc/import)").isEmpty();
    }

    /** Il nome qualificato fino alla prima classe (i segmenti di package sono minuscoli, le classi iniziano maiuscole). */
    private static String typeOf(String reference) {
        StringBuilder out = new StringBuilder();
        for (String segment : reference.split("\\.")) {
            if (segment.isEmpty()) {
                break;
            }
            out.append(out.isEmpty() ? "" : ".").append(segment);
            if (Character.isUpperCase(segment.charAt(0))) {
                break;
            }
        }
        return out.toString();
    }

    private static String packageOf(String reference) {
        String type = typeOf(reference);
        int lastDot = type.lastIndexOf('.');
        return Character.isUpperCase(type.charAt(lastDot + 1)) ? type.substring(0, lastDot) : type;
    }

    private static String check(Matcher from, Matcher to, String type) {
        String fromLayer = from.group(1);
        String toLayer = to.group(1);
        String fromRest = from.group(3) == null ? "" : from.group(3);
        String toRest = to.group(3) == null ? "" : to.group(3);
        if (fromLayer.equals("core") && toLayer.equals("app")) {
            return "il core non conosce l'app";
        }
        boolean sameSubsystem = fromLayer.equals(toLayer) && from.group(2).equals(to.group(2));
        boolean shared = toLayer.equals("core") && (to.group(2).equals("kernel") || to.group(2).equals("web"));
        if (shared) {
            return null;
        }
        if (isIn(fromRest, "domain") && !isIn(toRest, "domain")) {
            return "il domain dipende solo dal domain (e dal kernel)";
        }
        if (isIn(fromRest, "port") && !(isIn(toRest, "domain") || isIn(toRest, "port"))) {
            return "una porta dipende solo da domain e port";
        }
        if (!sameSubsystem) {
            boolean extensionPoint = fromLayer.equals("app") && fromRest.startsWith("adapter.")
                    && ArchitectureTest.CORE_EXTENSION_POINTS.contains(type);
            return isIn(toRest, "domain") || toRest.equals("port.in") || toRest.startsWith("port.in.") || extensionPoint
                    ? null : "fra sottosistemi si usano solo port.in e domain";
        }
        if (isIn(fromRest, "application") && toRest.startsWith("adapter")) {
            return "application non usa gli adapter";
        }
        if (fromRest.startsWith("adapter.in") && isIn(toRest, "port.out")) {
            return "un adapter in parla con le port.in, non con le port.out";
        }
        if (fromRest.startsWith("adapter.in") && toRest.startsWith("adapter.out")) {
            return "un adapter in non usa un adapter out";
        }
        if (fromRest.startsWith("adapter.out") && toRest.startsWith("adapter.in")) {
            return "un adapter out non usa un adapter in";
        }
        return null;
    }

    private static boolean isIn(String rest, String layer) {
        return rest.equals(layer) || rest.startsWith(layer + ".");
    }
}
