package org.dual.replicate.core.manual.adapter.out.markdown;

import org.dual.replicate.core.manual.domain.ManualHeading;
import org.dual.replicate.core.manual.domain.RenderedMarkdown;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommonmarkRendererTest {

    private final CommonmarkRenderer renderer = new CommonmarkRenderer();

    @Test
    void headingsGetAnchorsFromTheSharedSlugFunctionAndTheirSourceLine() {
        RenderedMarkdown out = renderer.render("# Cos'è un LoRA\n\nTesto.\n\n## Più modelli\n\n## Più modelli\n\n### Dettaglio\n", "");

        assertThat(out.headings()).extracting(ManualHeading::id)
                .containsExactly("cos-e-un-lora", "piu-modelli", "piu-modelli-1", "dettaglio");
        assertThat(out.headings()).extracting(ManualHeading::line).containsExactly(1, 5, 7, 9);
        assertThat(out.headings()).extracting(ManualHeading::level).containsExactly(1, 2, 2, 3);
        assertThat(out.html()).contains("<h1 id=\"cos-e-un-lora\">").contains("<h2 id=\"piu-modelli-1\">");
        assertThat(out.title()).isEqualTo("Cos'è un LoRA");
    }

    @Test
    void aHashInsideACodeBlockIsNotAHeading() {
        RenderedMarkdown out = renderer.render("# Titolo\n\n```\n# non un titolo\n```\n", "");
        assertThat(out.headings()).hasSize(1);
    }

    @Test
    void linksAreRewrittenForThePrefixOfTheRequest() {
        String html = renderer.render("""
                [pagina](03-genera-immagini.md#parametri) e [altra](../02-architettura/01-panoramica.md) \
                e [app](/gallery?tab=favourites) e [qui](#sotto) e [fuori](https://example.org/x)
                """, "/app").html();

        assertThat(html).contains("href=\"/app/manual/genera-immagini#parametri\"")
                .contains("href=\"/app/manual/panoramica\"")
                .contains("href=\"/app/gallery?tab=favourites\"")
                .contains("href=\"#sotto\"")
                .contains("href=\"https://example.org/x\"").contains("target=\"_blank\"").contains("rel=\"noopener\"");
    }

    @Test
    void withoutAPrefixTheLinksStayAtTheRoot() {
        String html = renderer.render("[g](/gallery) [p](galleria.md)", "").html();
        assertThat(html).contains("href=\"/gallery\"").contains("href=\"/manual/galleria\"");
    }

    @Test
    void rawHtmlAndDangerousUrlsDoNotReachThePage() {
        String html = renderer.render("# T\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\nuna <b>riga</b>\n", "").html();

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;")
                .doesNotContain("javascript:").doesNotContain("<b>");
    }

    @Test
    void tablesAreRendered() {
        String html = renderer.render("| a | b |\n|---|---|\n| 1 | 2 |\n", "").html();
        assertThat(html).contains("<table>").contains("<th>a</th>").contains("<td>2</td>");
    }

    @Test
    void theSummaryIsThePlainTextOfTheFirstTopLevelParagraphAndIsShortened() {
        RenderedMarkdown plain = renderer.render("# T\n\nUn **testo** con `codice` e un [link](/gallery)\nsu due righe.\n\nSecondo.\n", "");
        assertThat(plain.summary()).isEqualTo("Un testo con codice e un link su due righe.");

        RenderedMarkdown longer = renderer.render("# T\n\n" + "parola ".repeat(60) + "\n", "");
        assertThat(longer.summary()).hasSizeLessThanOrEqualTo(160).endsWith("…");

        RenderedMarkdown none = renderer.render("# T\n\n- solo un elenco\n", "");
        assertThat(none.summary()).isEmpty();
        assertThat(renderer.render("niente titolo", "").title()).isNull();
    }
}
