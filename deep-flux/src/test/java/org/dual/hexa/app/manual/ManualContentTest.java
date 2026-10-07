package org.dual.hexa.app.manual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.dual.hexa.core.manual.adapter.out.classpath.ClasspathManualSource;
import org.dual.hexa.core.manual.adapter.out.markdown.CommonmarkRenderer;
import org.dual.hexa.core.manual.application.ManualService;
import org.dual.hexa.core.manual.domain.ManualDocument;
import org.dual.hexa.core.manual.domain.ManualEntry;
import org.dual.hexa.core.manual.domain.ManualHeading;
import org.dual.hexa.core.manual.domain.ManualSection;
import org.dual.hexa.core.manual.domain.RenderedMarkdown;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il contenuto del manuale di QUESTA app (le pagine e i gruppi sono dell'app, non del motore {@code core.manual}: una nuova webapp scrive i suoi test).
 * Il manuale VERO (i {@code .md} di {@code src/main/resources/manual/it}), non un esempio: l'unica protezione contro un link rotto o una convenzione
 * saltata. Non puo' accorgersi di un testo diventato vecchio rispetto all'app: quello resta a chi cambia il comportamento (CLAUDE.md).
 * I link che puntano a una pagina dell'app (non del manuale) si controllano contro le rotte vere in {@code ManualControllerTest}.
 */
class ManualContentTest {

    /** Una sezione piu' lunga di cosi' non si legge piu' per intero dal bot (ManualTool taglia a 6.000 caratteri). */
    private static final int MAX_SECTION_CHARS = 6_000;
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    private final CommonmarkRenderer renderer = new CommonmarkRenderer();
    private final List<ManualDocument> documents = new ClasspathManualSource().documents("it");

    @Test
    void theManualHasTheTwoGroupsAndEverySlugIsUnique() {
        assertThat(documents).isNotEmpty();
        assertThat(documents.stream().map(ManualDocument::group).distinct()).containsExactly("uso", "architettura");
        Set<String> slugs = new HashSet<>();
        for (ManualDocument document : documents) {
            assertThat(slugs.add(document.slug())).as("slug duplicato: " + document.slug()).isTrue();
        }
        // E il servizio, con i sorgenti veri, costruisce l'indice senza errori.
        assertThat(new ManualService(language -> documents, renderer).contents("it")).hasSameSizeAs(documents);
    }

    @Test
    void everyPageHasOneTitleAndNoFormattingInsideHeadings() {
        for (ManualDocument document : documents) {
            RenderedMarkdown rendered = renderer.render(document.markdown(), "");
            List<ManualHeading> headings = rendered.headings();
            assertThat(headings.stream().filter(h -> h.level() == 1)).as(document.slug() + ": titoli di primo livello").hasSize(1);
            assertThat(headings.get(0).level()).as(document.slug() + ": la pagina comincia col titolo").isEqualTo(1);

            String[] lines = document.markdown().replace("\r\n", "\n").split("\n", -1);
            for (ManualHeading heading : headings) {
                String raw = lines[heading.line() - 1].replaceFirst("^#{1,6}\\s+", "").strip();
                assertThat(heading.text()).as(document.slug() + ": il titolo \"" + raw + "\" ha formattazione inline").isEqualTo(raw);
            }
            assertThat(rendered.summary()).as(document.slug() + ": riassunto (primo paragrafo)").isNotEmpty();
        }
    }

    @Test
    void everySectionIsSmallEnoughToBeReadByTheAssistant() {
        ManualService service = new ManualService(language -> documents, renderer);
        for (ManualEntry entry : service.contents("it")) {
            for (ManualSection section : service.sections(entry.slug(), "it")) {
                assertThat(section.markdown().length()).as(entry.slug() + "#" + section.anchor()).isLessThanOrEqualTo(MAX_SECTION_CHARS);
            }
        }
    }

    /**
     * Si controlla l'HTML gia' riscritto (prefisso vuoto): un link a una pagina diventa {@code /manual/slug#ancora}, quindi un {@code x.md} inesistente
     * ha uno slug sconosciuto, e un link senza forma riconoscibile (ne' {@code .md}, ne' percorso radice, ne' ancora) resta com'e' e fallisce qui.
     */
    @Test
    void everyLinkBetweenPagesResolvesToAPageAndAnExistingAnchor() {
        Map<String, Set<String>> anchorsBySlug = new HashMap<>();
        Map<String, String> htmlBySlug = new HashMap<>();
        for (ManualDocument document : documents) {
            RenderedMarkdown rendered = renderer.render(document.markdown(), "");
            anchorsBySlug.put(document.slug(), rendered.headings().stream().map(ManualHeading::id).collect(Collectors.toSet()));
            htmlBySlug.put(document.slug(), rendered.html());
        }
        htmlBySlug.forEach((slug, html) -> {
            Matcher matcher = HREF.matcher(html);
            while (matcher.find()) {
                String href = matcher.group(1);
                String where = slug + " -> " + href;
                if (href.startsWith("#")) {
                    assertThat(anchorsBySlug.get(slug)).as(where).contains(href.substring(1));
                } else if (href.equals("/manual")) {
                    continue;
                } else if (href.startsWith("/manual/")) {
                    String target = href.substring("/manual/".length());
                    int hash = target.indexOf('#');
                    String targetSlug = hash >= 0 ? target.substring(0, hash) : target;
                    assertThat(anchorsBySlug).as(where + " (pagina)").containsKey(targetSlug);
                    if (hash >= 0) {
                        assertThat(anchorsBySlug.get(targetSlug)).as(where + " (ancora)").contains(target.substring(hash + 1));
                    }
                } else {
                    assertThat(href).as(where + ": link non riconosciuto").matches("^(/[^/].*|/|https?://.*)$");
                }
            }
        });
    }

    @Test
    void noPageWritesALocalAddressOrAPort() {
        for (ManualDocument document : documents) {
            assertThat(document.markdown()).as(document.slug()).doesNotContainPattern("localhost|127\\.0\\.0\\.1|:\\d{4}\\b");
        }
    }
}
