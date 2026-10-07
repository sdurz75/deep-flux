package org.hexa.app.training.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Le regole delle didascalie sull'immagine: chi ha diritto di scrivere, e che una didascalia a mano non la tocca mai un lavoro automatico. */
class TrainingImageCaptionTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private TrainingImage newImage() {
        return new TrainingDataset("n", "TOK", LoraType.SUBJECT, null, NOW).addImage("a.png", "a.png", NOW);
    }

    private TrainingImage pendingImage() {
        TrainingImage image = newImage();
        image.requestAutoCaption(false);
        return image;
    }

    @Test
    void aNewImageHasNoCaptionAndNothingPending() {
        TrainingImage image = newImage();

        assertThat(image.getCaption()).isNull();
        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.NONE);
        assertThat(image.isCaptionPending()).isFalse();
    }

    @Test
    void anAutomaticCaptionIsWrittenWhileTheImageIsStillPending() {
        TrainingImage image = pendingImage();

        assertThat(image.applyAutoCaption("a.png", "TOK, un gatto")).isTrue();

        assertThat(image.getCaption()).isEqualTo("TOK, un gatto");
        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.AUTO);
        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
    }

    @Test
    void anAutomaticCaptionForANotPendingImageIsDiscarded() {
        TrainingImage image = newImage();

        assertThat(image.applyAutoCaption("a.png", "TOK, un gatto")).isFalse();

        assertThat(image.getCaption()).isNull();
    }

    @Test
    void anAutomaticCaptionNeverOverwritesOneWrittenByHand() {
        TrainingImage image = pendingImage();
        image.writeCaption("scritta a mano"); // l'utente scrive mentre il lavoro e' in corso

        assertThat(image.applyAutoCaption("a.png", "TOK, automatica")).isFalse();
        assertThat(image.failAutoCaption("a.png")).isFalse();

        assertThat(image.getCaption()).isEqualTo("scritta a mano");
        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.MANUAL);
        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
    }

    @Test
    void aCaptionOfAnotherFileIsDiscardedBecauseTheImageWasCroppedMeanwhile() {
        TrainingImage image = pendingImage();
        image.applyCrop("ritaglio.jpg", 0, 0, 10, 10);

        assertThat(image.applyAutoCaption("a.png", "TOK, vecchia inquadratura")).isFalse();
        assertThat(image.failAutoCaption("a.png")).isFalse();

        assertThat(image.getCaption()).isNull();
        assertThat(image.isCaptionPending()).isTrue();
    }

    @Test
    void aFailureKeepsThePreviousTextAndMarksTheImageFailed() {
        TrainingImage image = pendingImage();
        image.applyAutoCaption("a.png", "TOK, prima");
        image.requestAutoCaption(false);

        assertThat(image.failAutoCaption("a.png")).isTrue();

        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.FAILED);
        assertThat(image.getCaption()).isEqualTo("TOK, prima");
    }

    @Test
    void requestingAgainKeepsTheCaptionTextUntilTheNewOneArrives() {
        TrainingImage image = pendingImage();
        image.applyAutoCaption("a.png", "TOK, prima");

        assertThat(image.requestAutoCaption(false)).isTrue();

        assertThat(image.isCaptionPending()).isTrue();
        assertThat(image.getCaption()).isEqualTo("TOK, prima");
    }

    @Test
    void aManualCaptionIsOnlyRequestedAgainOnExplicitRequest() {
        TrainingImage image = newImage();
        image.writeCaption("scritta a mano");

        assertThat(image.requestAutoCaption(false)).isFalse();
        assertThat(image.isCaptionPending()).isFalse();
        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.MANUAL);

        assertThat(image.requestAutoCaption(true)).isTrue();
        assertThat(image.isCaptionPending()).isTrue();
        assertThat(image.getCaptionSource()).as("da ora e' di nuovo da scrivere a macchina: il lavoro puo' scrivere").isEqualTo(CaptionSource.NONE);
        assertThat(image.applyAutoCaption("a.png", "TOK, nuova")).isTrue();
    }

    @Test
    void writingAnEmptyCaptionRemovesIt() {
        TrainingImage image = newImage();
        image.writeCaption("qualcosa");

        image.writeCaption("");

        assertThat(image.getCaption()).isNull();
        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.NONE);
        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
    }

    @Test
    void theTriggerWordIsOnlyAddedToACaptionThatLacksItIgnoringCase() {
        TrainingImage lacking = newImage();
        lacking.writeCaption("un gatto su un divano");
        TrainingImage present = newImage();
        present.writeCaption("a photo of tok on a sofa");
        TrainingImage empty = newImage();

        assertThat(lacking.isCaptionMissingTrigger("TOK")).isTrue();
        assertThat(present.isCaptionMissingTrigger("TOK")).isFalse();
        assertThat(empty.isCaptionMissingTrigger("TOK")).as("senza didascalia non c'e' nulla a cui aggiungerla").isFalse();

        assertThat(lacking.prependTriggerWord("TOK")).isTrue();
        assertThat(present.prependTriggerWord("TOK")).isFalse();
        assertThat(empty.prependTriggerWord("TOK")).isFalse();
        assertThat(lacking.getCaption()).isEqualTo("TOK, un gatto su un divano");
        assertThat(present.getCaption()).isEqualTo("a photo of tok on a sofa");
        assertThat(empty.getCaption()).isNull();
    }

    @Test
    void addingTheTriggerWordKeepsTheOriginOfTheCaption() {
        TrainingImage image = pendingImage();
        image.applyAutoCaption("a.png", "un gatto");

        image.prependTriggerWord("TOK");

        assertThat(image.getCaptionSource()).isEqualTo(CaptionSource.AUTO);
    }
}
