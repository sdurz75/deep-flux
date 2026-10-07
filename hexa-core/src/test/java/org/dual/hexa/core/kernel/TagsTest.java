package org.dual.hexa.core.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TagsTest {

    @Test
    void normalizeTrimsLowercasesAndCollapsesSpaces() {
        assertThat(Tags.normalize("  Vacanze   2026 ")).isEqualTo("vacanze 2026");
        assertThat(Tags.normalize(null)).isEmpty();
        assertThat(Tags.normalize("   ")).isEmpty();
    }

    @Test
    void normalizeRemovesCharactersThatBreakTheCsvAndTheJsonpathFilter() {
        assertThat(Tags.normalize("a,b")).isEqualTo("a b");
        assertThat(Tags.normalize("\"quoted\\\"")).isEqualTo("quoted");
    }

    @Test
    void normalizeCapsTheLength() {
        assertThat(Tags.normalize("x".repeat(100))).hasSize(Tags.MAX_LENGTH);
    }

    @Test
    void parseSplitsOnCommasDropsEmptiesAndDuplicates() {
        assertThat(Tags.parse("Mare, mare ,,Montagna")).containsExactly("mare", "montagna");
        assertThat(Tags.parse(null)).isEmpty();
    }
}
