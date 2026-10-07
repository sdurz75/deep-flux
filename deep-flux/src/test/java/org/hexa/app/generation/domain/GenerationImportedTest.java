package org.hexa.app.generation.domain;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationImportedTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void anImportedImageIsAlreadySucceededWithNoModelAndAPendingAnalysis() {
        Generation g = Generation.imported("a.png", NOW);

        assertThat(g.isImported()).isTrue();
        assertThat(g.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(g.getKind()).isEqualTo(GenerationKind.IMAGE);
        assertThat(g.getModel()).isNull();
        assertThat(g.getExternalId()).isNull();
        assertThat(g.getPrompt()).isEmpty();
        assertThat(g.getImageFilenames()).containsExactly("a.png");
        assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(g.getCompletedAt()).isEqualTo(NOW);
        assertThat(g.isTerminal()).isTrue();
    }

    @Test
    void aGeneratedRowIsNotImportedAndHasNoAnalysis() {
        Generation g = new Generation("ext", "owner/model", null, "p", null);

        assertThat(g.isImported()).isFalse();
        assertThat(g.getOrigin()).isEqualTo(GenerationOrigin.GENERATED);
        assertThat(g.getAnalysisStatus()).isNull();
        assertThat(g.getAnalysisTagList()).isEmpty();
    }

    @Test
    void analysisLifecycleFailRetryApply() {
        Generation g = Generation.imported("a.png", NOW);

        g.failAnalysis("rifiutata");
        assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(g.getErrorMessage()).isEqualTo("rifiutata");

        g.restartAnalysis();
        assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(g.getErrorMessage()).isNull();

        g.applyAnalysis("Un gatto.", List.of("gatto", "cat"));
        assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(g.getPrompt()).isEqualTo("Un gatto.");
        assertThat(g.getAnalysisTagList()).containsExactly("gatto", "cat");
    }
}
