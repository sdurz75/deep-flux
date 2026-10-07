package org.dual.hexa.core.manual.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeadingSlugsTest {

    @Test
    void anchorsAreAsciiLowercaseWithoutAccentsOrPunctuation() {
        assertThat(HeadingSlugs.slug("Cos'e' un LoRA?")).isEqualTo("cos-e-un-lora");
        assertThat(HeadingSlugs.slug("Più modelli, più scelta")).isEqualTo("piu-modelli-piu-scelta");
        assertThat(HeadingSlugs.slug("  Galleria e preferiti  ")).isEqualTo("galleria-e-preferiti");
    }

    @Test
    void repeatedAndCollidingTitlesGetDistinctAnchors() {
        HeadingSlugs slugs = new HeadingSlugs();
        assertThat(slugs.next("Parametri")).isEqualTo("parametri");
        assertThat(slugs.next("Parametri")).isEqualTo("parametri-1");
        assertThat(slugs.next("Parametri 1")).isEqualTo("parametri-1-1");
        assertThat(slugs.next("parametri-1")).isEqualTo("parametri-1-2");
        assertThat(slugs.next("???")).isEqualTo("sezione");
    }
}
