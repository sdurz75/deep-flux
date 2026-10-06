package org.dual.replicate.app.training.application;

import java.time.Instant;

import org.dual.replicate.app.training.domain.CaptionSource;
import org.dual.replicate.app.training.domain.CaptionStatus;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.event.CaptionRequestedEvent;
import org.dual.replicate.app.training.port.in.ITrainingCaptions;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Le azioni dell'utente sulle didascalie, con store in memoria (con versioni vere) ed eventi finti. */
class TrainingCaptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final InMemoryDatasetStore store = new InMemoryDatasetStore();
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Messages messages = mock(Messages.class);
    private TrainingCaptionService service;

    private TrainingDataset dataset;
    private Long autoImage;
    private Long manualImage;
    private Long emptyImage;
    private Long failedImage;

    @BeforeEach
    void setUp() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        service = new TrainingCaptionService(new DatasetEditor(store, messages), publisher, messages);

        TrainingDataset created = new TrainingDataset("gatto", "TOKCAT", LoraType.SUBJECT, null, NOW);
        TrainingImage auto = created.addImage("a.png", "a.png", NOW);
        TrainingImage manual = created.addImage("b.png", "b.png", NOW);
        TrainingImage empty = created.addImage("c.png", "c.png", NOW);
        TrainingImage failed = created.addImage("d.png", "d.png", NOW);
        auto.requestAutoCaption(false);
        auto.applyAutoCaption("a.png", "un gatto su un divano"); // AUTO senza trigger word
        manual.writeCaption("scritta a mano senza trigger");
        failed.requestAutoCaption(false);
        failed.failAutoCaption("d.png");
        dataset = store.save(created);
        autoImage = dataset.getImages().get(0).getId();
        manualImage = dataset.getImages().get(1).getId();
        emptyImage = dataset.getImages().get(2).getId();
        failedImage = dataset.getImages().get(3).getId();
        assertThat(empty.getCaption()).isNull();
    }

    private TrainingImage image(Long id) {
        return store.image(dataset.getId(), id);
    }

    // --- scrittura a mano ---------------------------------------------------------------------------------------

    @Test
    void aHandWrittenCaptionIsTrimmedStoredAndMarkedManual() {
        service.saveCaption(dataset.getId(), autoImage, "  TOKCAT, corretta da me  ");

        assertThat(image(autoImage).getCaption()).isEqualTo("TOKCAT, corretta da me");
        assertThat(image(autoImage).getCaptionSource()).isEqualTo(CaptionSource.MANUAL);
        assertThat(image(autoImage).getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void anEmptyTextRemovesTheCaption() {
        service.saveCaption(dataset.getId(), manualImage, "   ");

        assertThat(image(manualImage).getCaption()).isNull();
        assertThat(image(manualImage).getCaptionSource()).isEqualTo(CaptionSource.NONE);
    }

    @Test
    void aCaptionTooLongIsRejectedAndSavesNothing() {
        assertThatThrownBy(() -> service.saveCaption(dataset.getId(), autoImage, "x".repeat(ITrainingCaptions.MAX_CAPTION + 1)))
                .isInstanceOfSatisfying(TrainingException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("training.error.captionTooLong");
                    assertThat(e.isReportable()).isFalse();
                });

        assertThat(image(autoImage).getCaptionSource()).isEqualTo(CaptionSource.AUTO);
    }

    @Test
    void saveCaptionOfAnImageOfAnotherDatasetOrAFrozenOneIsRejected() {
        TrainingDataset other = store.save(new TrainingDataset("altro", "TOK", LoraType.SUBJECT, null, NOW));
        TrainingDataset frozen = new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, null, NOW);
        frozen.addImage("s.png", "s.png", NOW);
        TrainingDataset savedFrozen = store.save(frozen);

        assertThatThrownBy(() -> service.saveCaption(other.getId(), autoImage, "x")).isInstanceOf(TrainingException.class)
                .hasMessage("training.error.imageNotFound");
        assertThatThrownBy(() -> service.saveCaption(savedFrozen.getId(), savedFrozen.getImages().get(0).getId(), "x"))
                .isInstanceOf(TrainingException.class).hasMessage("training.error.frozen");
    }

    @Test
    void aHandWrittenCaptionSurvivesAnAutomaticOneArrivingBetweenReadAndSave() {
        store.beforeNextSave = () -> store.concurrently(dataset.getId(), d -> d.findImage(failedImage).orElseThrow().requestAutoCaption(false));

        service.saveCaption(dataset.getId(), autoImage, "TOKCAT, mia");

        assertThat(image(autoImage).getCaption()).isEqualTo("TOKCAT, mia");
        assertThat(image(failedImage).isCaptionPending()).as("la modifica concorrente su un'altra immagine non va persa").isTrue();
    }

    // --- rigenera -----------------------------------------------------------------------------------------------

    @Test
    void regeneratingOneImageOverwritesEvenAHandWrittenCaptionBecauseItIsExplicit() {
        service.recaption(dataset.getId(), manualImage);

        assertThat(image(manualImage).isCaptionPending()).isTrue();
        assertThat(image(manualImage).getCaptionSource()).isEqualTo(CaptionSource.NONE);
        assertThat(image(manualImage).getCaption()).as("il testo resta finche' non arriva il nuovo").isEqualTo("scritta a mano senza trigger");
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), manualImage));
    }

    @Test
    void regeneratingAllRestartsTheAutomaticMissingAndFailedOnesButNeverTheHandWrittenOnes() {
        service.recaptionAutomatic(dataset.getId());

        assertThat(image(autoImage).isCaptionPending()).isTrue();
        assertThat(image(emptyImage).isCaptionPending()).isTrue();
        assertThat(image(failedImage).isCaptionPending()).isTrue();
        assertThat(image(manualImage).isCaptionPending()).isFalse();
        assertThat(image(manualImage).getCaption()).isEqualTo("scritta a mano senza trigger");
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), autoImage));
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), emptyImage));
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), failedImage));
        verify(publisher, never()).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), manualImage));
    }

    @Test
    void regeneratingAllDoesNotQueueASecondJobForAnImageAlreadyPending() {
        store.concurrently(dataset.getId(), d -> d.findImage(emptyImage).orElseThrow().requestAutoCaption(false)); // gia' in coda

        service.recaptionAutomatic(dataset.getId());

        verify(publisher, never()).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), emptyImage));
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), autoImage));
    }

    @Test
    void noEventIsPublishedWhenTheSaveFails() {
        store.failOnSave = new IllegalStateException("db giu'");

        assertThatThrownBy(() -> service.recaptionAutomatic(dataset.getId())).isInstanceOf(IllegalStateException.class);

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // --- trigger word -------------------------------------------------------------------------------------------

    @Test
    void theTriggerWordIsPutInFrontOfTheCaptionsThatLackItAndOnlyThose() {
        store.concurrently(dataset.getId(), d -> d.findImage(failedImage).orElseThrow().writeCaption("TOKCAT, gia' c'e'"));

        service.addTriggerWord(dataset.getId());

        assertThat(image(autoImage).getCaption()).isEqualTo("TOKCAT, un gatto su un divano");
        assertThat(image(autoImage).getCaptionSource()).as("l'origine non cambia").isEqualTo(CaptionSource.AUTO);
        assertThat(image(manualImage).getCaption()).isEqualTo("TOKCAT, scritta a mano senza trigger");
        assertThat(image(manualImage).getCaptionSource()).isEqualTo(CaptionSource.MANUAL);
        assertThat(image(failedImage).getCaption()).isEqualTo("TOKCAT, gia' c'e'");
        assertThat(image(emptyImage).getCaption()).as("senza didascalia non se ne inventa una").isNull();
    }
}
