package org.dual.hexa.ai.chat.adapter.ai;

import java.util.List;

import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.manual.adapter.out.markdown.CommonmarkRenderer;
import org.dual.hexa.core.manual.application.ManualService;
import org.dual.hexa.core.manual.domain.ManualDocument;
import org.dual.hexa.core.manual.port.out.IManualSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ManualToolTest {

    private static final String GALLERY = """
            # Galleria

            La galleria mostra le immagini riuscite.

            ## Preferiti

            Ogni immagine può avere la stella.

            ## Cancellare

            Si seleziona e si cancella.
            """;
    private static final String ARCH = """
            # Panoramica

            Gli esagoni del sistema.

            ## Esagoni

            Core e app, ports and adapters.
            """;

    private final ISystemEvents systemEvents = mock(ISystemEvents.class);

    private ManualTool tool(List<ManualDocument> documents) {
        IManualSource source = language -> documents;
        return new ManualTool(new ManualService(source, new CommonmarkRenderer()), systemEvents);
    }

    private ManualTool standard() {
        return tool(List.of(new ManualDocument("uso", "galleria", GALLERY), new ManualDocument("architettura", "panoramica", ARCH)));
    }

    @Test
    void anEmptyQueryListsTheUserPagesOnly() {
        String contents = standard().searchManual("  ");

        assertThat(contents).contains("- galleria: Galleria - La galleria mostra le immagini riuscite. (/manual/galleria)")
                .doesNotContain("panoramica");
    }

    @Test
    void aSearchReturnsTheBestSectionsWithACitableLink() {
        String result = standard().searchManual("come metto la stella ai preferiti?");

        assertThat(result).contains("## Galleria > Preferiti").contains("Link: /manual/galleria#preferiti").contains("Ogni immagine può avere la stella.");
    }

    @Test
    void theArchitecturePartIsNotReachableFromTheChat() {
        assertThat(standard().searchManual("esagoni ports adapters")).contains("does not cover it");
        assertThat(standard().readManualPage("panoramica", null)).contains("No manual page").contains("Valid pages: galleria");
    }

    @Test
    void aSearchWithoutResultsTellsTheModelNotToGuess() {
        assertThat(standard().searchManual("zxqwv")).contains("does not cover it").contains("empty query");
    }

    @Test
    void readingAPageReturnsAllItsSections() {
        String page = standard().readManualPage("galleria", null);

        assertThat(page).startsWith("Manual page \"Galleria\" (/manual/galleria):").contains("# Galleria").contains("## Preferiti").contains("## Cancellare")
                .doesNotContain("Not shown");
    }

    @Test
    void readingASectionAcceptsTheAnchorOrTheCitedLink() {
        String bySection = standard().readManualPage("galleria", "preferiti");
        assertThat(bySection).contains("section \"Preferiti\" (/manual/galleria#preferiti)").contains("la stella").doesNotContain("Si seleziona");

        assertThat(standard().readManualPage("/manual/galleria#cancellare", null)).contains("Si seleziona e si cancella.").doesNotContain("la stella");
        String byFileName = standard().readManualPage("03-galleria.md", "#preferiti");
        assertThat(byFileName).contains("la stella").doesNotContain("Si seleziona").doesNotContain("No manual page");
        assertThat(standard().readManualPage("Galleria", "Preferiti")).contains("la stella");
    }

    @Test
    void anUnknownPageOrSectionAnswersWithTheValidOnes() {
        assertThat(standard().readManualPage("non-esiste", null)).contains("Valid pages: galleria");
        assertThat(standard().readManualPage(" ", null)).contains("Give the page slug");
        assertThat(standard().readManualPage("galleria", "nulla")).contains("Sections: galleria (Galleria), preferiti (Preferiti), cancellare (Cancellare)");
        assertThat(standard().readManualPage("../../etc/passwd", null)).contains("No manual page");
    }

    @Test
    void aLongPageIsCutAndTheRemainingSectionsAreListed() {
        String filler = "parola ".repeat(500);
        String longPage = "# Lunga\n\nIntro.\n\n## Uno\n\n" + filler + "\n\n## Due\n\n" + filler + "\n\n## Tre\n\nFine.\n";
        ManualTool tool = tool(List.of(new ManualDocument("uso", "lunga", longPage)));

        String page = tool.readManualPage("lunga", null);

        assertThat(page.length()).isLessThanOrEqualTo(6_400);
        assertThat(page).contains("## Uno").contains("Not shown here").contains("due (Due)").contains("tre (Tre)");
    }

    @Test
    void aVeryLongSectionIsCutWithAHint() {
        ManualTool tool = tool(List.of(new ManualDocument("uso", "lunga", "# Lunga\n\n## Enorme\n\n" + "parola ".repeat(2_000) + "\n")));

        String section = tool.readManualPage("lunga", "enorme");

        assertThat(section.length()).isLessThan(6_200);
        assertThat(section).contains("[...cut");
        assertThat(tool.searchManual("parola")).contains("[...cut: read the whole section with readManualPage]");
    }

    @Test
    void aFailureIsRecordedAndTheModelIsToldToSayItCouldNotConsultTheManual() {
        IManualSource broken = language -> {
            throw new IllegalStateException("classpath illeggibile");
        };
        ManualTool tool = new ManualTool(new ManualService(broken, new CommonmarkRenderer()), systemEvents);

        assertThat(tool.searchManual("galleria")).contains("not available right now").contains("could not consult");
        assertThat(tool.readManualPage("galleria", null)).contains("not available right now");
        verify(systemEvents).record(eq("searchManual"), any(RuntimeException.class));
        verify(systemEvents).record(eq("readManualPage"), any(RuntimeException.class));
    }

    @Test
    void theToolkitHasItsPromptSection() {
        assertThat(standard().promptSection()).isEqualTo("deep-chat.section.manual");
    }
}
