package org.dual.replicate.core.manual.application;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.core.manual.adapter.out.markdown.CommonmarkRenderer;
import org.dual.replicate.core.manual.domain.ManualDocument;
import org.dual.replicate.core.manual.domain.ManualEntry;
import org.dual.replicate.core.manual.domain.ManualException;
import org.dual.replicate.core.manual.domain.ManualHit;
import org.dual.replicate.core.manual.domain.ManualPage;
import org.dual.replicate.core.manual.domain.ManualSection;
import org.dual.replicate.core.manual.port.out.IManualSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualServiceTest {

    private static final String GALLERY = """
            # Galleria e preferiti

            La galleria mostra le immagini riuscite.

            ## Preferiti

            Ogni immagine può avere la stella. I preferiti si vedono nella scheda dedicata.

            ### Come si toglie

            Si preme di nuovo la stella.

            ## Cancellare

            Si seleziona e si cancella.
            """;
    private static final String VIDEO = """
            # Video

            Si anima un'immagine.

            ## Anima

            Il bottone anima parte dalla miniatura.
            """;
    private static final String ARCH = """
            # Panoramica

            Come è fatto il sistema.

            ## Esagoni

            Core e app.
            """;

    private static ManualService service(ManualDocument... documents) {
        IManualSource source = language -> List.of(documents);
        return new ManualService(source, new CommonmarkRenderer());
    }

    private static ManualService standard() {
        return service(new ManualDocument("uso", "galleria", GALLERY), new ManualDocument("uso", "video", VIDEO),
                new ManualDocument("architettura", "panoramica", ARCH));
    }

    @Test
    void theIndexKeepsTheSourceOrderAndTakesTitleAndSummaryFromTheText() {
        List<ManualEntry> contents = standard().contents("it");

        assertThat(contents).extracting(ManualEntry::slug).containsExactly("galleria", "video", "panoramica");
        assertThat(contents.get(0)).isEqualTo(new ManualEntry("uso", "galleria", "Galleria e preferiti", "La galleria mostra le immagini riuscite."));
        assertThat(contents).extracting(ManualEntry::group).containsExactly("uso", "uso", "architettura");
    }

    @Test
    void twoPagesWithTheSameSlugAreAnInstallationError() {
        ManualService service = service(new ManualDocument("uso", "galleria", GALLERY), new ManualDocument("architettura", "galleria", ARCH));
        assertThatThrownBy(() -> service.contents("it")).isInstanceOf(ManualException.class).hasMessageContaining("galleria");
    }

    @Test
    void aPageIsRenderedWithThePrefixAndAnUnknownSlugIsEmpty() {
        ManualService service = standard();

        Optional<ManualPage> page = service.page("galleria", "it", "/proxy");
        assertThat(page).isPresent();
        assertThat(page.get().html()).contains("<h2 id=\"preferiti\">");
        assertThat(page.get().headings()).hasSize(4);

        assertThat(service.page("non-esiste", "it", "")).isEmpty();
        // Lo slug e' una chiave di mappa, mai un percorso.
        assertThat(service.page("../../../etc/passwd", "it", "")).isEmpty();
        assertThat(service.page("01-uso/01-galleria", "it", "")).isEmpty();
    }

    @Test
    void sectionsStartAtLevelOneAndTwoHeadingsAndKeepTheirSubheadings() {
        List<ManualSection> sections = standard().sections("galleria", "it");

        assertThat(sections).extracting(ManualSection::title).containsExactly("Galleria e preferiti", "Preferiti", "Cancellare");
        assertThat(sections).extracting(ManualSection::anchor).containsExactly("galleria-e-preferiti", "preferiti", "cancellare");
        assertThat(sections.get(0).markdown()).startsWith("# Galleria e preferiti").contains("immagini riuscite").doesNotContain("stella");
        assertThat(sections.get(1).markdown()).startsWith("## Preferiti").contains("### Come si toglie").contains("Si preme di nuovo la stella.")
                .doesNotContain("Si seleziona");
        assertThat(sections.get(2).markdown()).isEqualTo("## Cancellare\n\nSi seleziona e si cancella.");
        assertThat(standard().sections("non-esiste", "it")).isEmpty();
    }

    @Test
    void everySectionAnchorIsAnIdOfTheRenderedPage() {
        ManualService service = standard();
        for (ManualEntry entry : service.contents("it")) {
            String html = service.page(entry.slug(), "it", "").orElseThrow().html();
            for (ManualSection section : service.sections(entry.slug(), "it")) {
                assertThat(html).as(entry.slug() + "#" + section.anchor()).contains("id=\"" + section.anchor() + "\"");
            }
        }
    }

    @Test
    void searchRanksTheSectionWhoseTitleMatchesFirst() {
        List<ManualHit> hits = standard().search("preferiti", null, "it", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).section().slug()).isEqualTo("galleria");
        assertThat(hits.get(0).section().anchor()).isEqualTo("preferiti");
        assertThat(hits.get(0).pageTitle()).isEqualTo("Galleria e preferiti");
        assertThat(hits).isSortedAccordingTo((a, b) -> Integer.compare(b.score(), a.score()));
    }

    @Test
    void searchIgnoresAccentsCaseAndTheNumberOfTheWord() {
        ManualService service = standard();
        assertThat(service.search("PIU", null, "it", 3)).isEmpty(); // "piu" e' una parola comune, scartata
        assertThat(service.search("GALLERIE immagine", null, "it", 3)).isNotEmpty();
        // Il plurale della domanda trova il singolare del testo e viceversa.
        assertThat(service.search("immagini", null, "it", 5)).extracting(h -> h.section().slug()).contains("galleria", "video");
        assertThat(service.search("preferito", null, "it", 5)).extracting(h -> h.section().slug()).contains("galleria");
        // Un accento nel testo ("può") si trova senza accento.
        assertThat(service.search("puo avere stella", null, "it", 3)).extracting(h -> h.section().anchor()).contains("preferiti");
    }

    @Test
    void searchCanBeLimitedToAGroupAndReturnsNothingForNoise() {
        ManualService service = standard();

        assertThat(service.search("sistema esagoni", "uso", "it", 5)).isEmpty();
        // Due sezioni della stessa pagina (l'introduzione dice "sistema", "Esagoni" e' la seconda), nessuna di un'altra.
        assertThat(service.search("sistema esagoni", "architettura", "it", 5)).extracting(h -> h.section().anchor())
                .containsExactlyInAnyOrder("panoramica", "esagoni");
        assertThat(service.search("come dove per", null, "it", 5)).isEmpty();
        assertThat(service.search("", null, "it", 5)).isEmpty();
        assertThat(service.search(null, null, "it", 5)).isEmpty();
        assertThat(service.search("galleria", null, "it", 0)).isEmpty();
        assertThat(service.search("galleria", null, "it", 1)).hasSize(1);
    }
}
