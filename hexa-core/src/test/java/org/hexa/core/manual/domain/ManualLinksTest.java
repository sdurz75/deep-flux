package org.hexa.core.manual.domain;

import org.hexa.core.manual.domain.ManualLinks.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManualLinksTest {

    @Test
    void aPageLinkNamesTheRealFileAndDropsTheOrderPrefixAndFolders() {
        ManualLinks.Target same = ManualLinks.classify("03-genera-immagini.md");
        assertThat(same.kind()).isEqualTo(Kind.PAGE);
        assertThat(same.slug()).isEqualTo("genera-immagini");
        assertThat(same.anchor()).isEmpty();

        ManualLinks.Target other = ManualLinks.classify("../02-architettura/01-panoramica.md#hypermedia-first");
        assertThat(other.kind()).isEqualTo(Kind.PAGE);
        assertThat(other.slug()).isEqualTo("panoramica");
        assertThat(other.anchor()).isEqualTo("hypermedia-first");

        assertThat(ManualLinks.classify("./galleria.md").slug()).isEqualTo("galleria");
    }

    @Test
    void otherLinksAreClassifiedByTheirShape() {
        assertThat(ManualLinks.classify("#parametri").kind()).isEqualTo(Kind.ANCHOR);
        assertThat(ManualLinks.classify("#parametri").anchor()).isEqualTo("parametri");
        assertThat(ManualLinks.classify("/generations/new?kind=video").kind()).isEqualTo(Kind.APP);
        assertThat(ManualLinks.classify("/generations/new?kind=video").path()).isEqualTo("/generations/new?kind=video");
        assertThat(ManualLinks.classify("https://example.org/x").kind()).isEqualTo(Kind.EXTERNAL);
        assertThat(ManualLinks.classify("mailto:a@b.c").kind()).isEqualTo(Kind.EXTERNAL);
        assertThat(ManualLinks.classify("//cdn.example.org/x").kind()).isEqualTo(Kind.EXTERNAL);
        assertThat(ManualLinks.classify("immagine.png").kind()).isEqualTo(Kind.OTHER);
        assertThat(ManualLinks.classify("").kind()).isEqualTo(Kind.OTHER);
        assertThat(ManualLinks.classify(null).kind()).isEqualTo(Kind.OTHER);
    }

    @Test
    void theOrderPrefixIsRemovedOnlyWhenItIsLeadingDigits() {
        assertThat(ManualLinks.withoutOrder("01-uso")).isEqualTo("uso");
        assertThat(ManualLinks.withoutOrder("12-genera-immagini")).isEqualTo("genera-immagini");
        assertThat(ManualLinks.withoutOrder("galleria-2")).isEqualTo("galleria-2");
    }
}
