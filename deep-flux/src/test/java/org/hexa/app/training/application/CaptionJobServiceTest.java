package org.hexa.app.training.application;

import java.time.Instant;

import org.hexa.app.shared.domain.AppEventSource;
import org.hexa.core.ai.domain.CaptionStyle;
import org.hexa.core.ai.domain.ImageCaptionException;
import org.hexa.core.ai.port.in.IImageCaptioner;
import org.hexa.core.ai.domain.OpenRouterException;
import org.hexa.app.training.domain.CaptionSource;
import org.hexa.app.training.domain.CaptionStatus;
import org.hexa.app.training.domain.LoraType;
import org.hexa.app.training.domain.TrainingDataset;
import org.hexa.app.training.domain.TrainingImage;
import org.hexa.app.training.domain.event.CaptionRequestedEvent;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.hexa.core.storage.domain.SourceImage;
import org.hexa.core.storage.domain.StorageException;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Il lavoro di didascalia automatica con captioner, storage ed eventi finti. Il punto delicato e' la finestra fra la lettura e la scrittura, in cui il
 * modello di visione impiega secondi: l'immagine puo' sparire, essere ritagliata di nuovo o riscritta a mano, e il risultato non deve mai calpestarla.
 */
class CaptionJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final SourceImage BYTES = new SourceImage(new byte[]{1, 2, 3}, "image/png");

    private final InMemoryDatasetStore store = new InMemoryDatasetStore();
    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final IImageCaptioner captioner = mock(IImageCaptioner.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Messages messages = mock(Messages.class);
    private CaptionJobService service;

    private TrainingDataset dataset;
    private Long imageId;

    @BeforeEach
    void setUp() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        when(storage.read(anyString())).thenReturn(BYTES);
        service = new CaptionJobService(new DatasetEditor(store, messages), store, storage, captioner, systemEvents, publisher);

        TrainingDataset created = new TrainingDataset("gatto", "TOKCAT", LoraType.SUBJECT, null, NOW);
        created.addImage("a.png", "a.png", NOW).requestAutoCaption(false);
        dataset = store.save(created);
        imageId = dataset.getImages().get(0).getId();
    }

    private TrainingImage image() {
        return store.image(dataset.getId(), imageId);
    }

    @Test
    void writesTheAutomaticCaptionOfAPendingImage() {
        when(captioner.caption(BYTES, "TOKCAT", CaptionStyle.SUBJECT)).thenReturn("a photo of TOKCAT on a sofa");

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaption()).isEqualTo("a photo of TOKCAT on a sofa");
        assertThat(image().getCaptionSource()).isEqualTo(CaptionSource.AUTO);
        assertThat(image().getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
        verifyNoInteractions(systemEvents);
    }

    @Test
    void aStyleDatasetIsCaptionedWithTheStyleGuide() {
        store.concurrently(dataset.getId(), d -> d.update("stile", "WTRCLR", LoraType.STYLE, null, NOW));
        when(captioner.caption(BYTES, "WTRCLR", CaptionStyle.STYLE)).thenReturn("WTRCLR style, a lighthouse");

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaption()).isEqualTo("WTRCLR style, a lighthouse");
    }

    @Test
    void theCroppedFileIsTheOneThatGetsCaptioned() {
        store.concurrently(dataset.getId(), d -> d.findImage(imageId).orElseThrow().applyCrop("ritaglio.jpg", 0, 0, 10, 10));
        when(captioner.caption(any(), anyString(), any())).thenReturn("TOKCAT, ritagliata");

        service.caption(dataset.getId(), imageId);

        verify(storage).read("ritaglio.jpg");
        verify(storage, never()).read("a.png");
    }

    @Test
    void anImageThatIsNotPendingIsLeftAloneAndTheModelIsNeverCalled() {
        store.concurrently(dataset.getId(), d -> d.findImage(imageId).orElseThrow().applyAutoCaption("a.png", "TOKCAT, gia' fatta"));

        service.caption(dataset.getId(), imageId);

        verifyNoInteractions(captioner);
        assertThat(image().getCaption()).isEqualTo("TOKCAT, gia' fatta");
    }

    @Test
    void aManualCaptionIsNeverCaptionedAgain() {
        store.concurrently(dataset.getId(), d -> d.findImage(imageId).orElseThrow().writeCaption("scritta a mano"));

        service.caption(dataset.getId(), imageId);

        verifyNoInteractions(captioner);
        assertThat(image().getCaption()).isEqualTo("scritta a mano");
    }

    @Test
    void aCaptionWrittenByHandWhileTheModelWorksIsNotOverwrittenByTheResult() {
        when(captioner.caption(any(), anyString(), any())).thenAnswer(call -> {
            store.concurrently(dataset.getId(), d -> d.findImage(imageId).orElseThrow().writeCaption("l'utente ha scritto nel frattempo"));
            return "TOKCAT, automatica";
        });

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaption()).isEqualTo("l'utente ha scritto nel frattempo");
        assertThat(image().getCaptionSource()).isEqualTo(CaptionSource.MANUAL);
    }

    @Test
    void aResultForAnImageCroppedAgainWhileTheModelWorkedIsDiscardedAndTheImageStaysPending() {
        when(captioner.caption(any(), anyString(), any())).thenAnswer(call -> {
            store.concurrently(dataset.getId(), d -> d.findImage(imageId).orElseThrow().applyCrop("nuovo-ritaglio.jpg", 5, 5, 20, 20));
            return "TOKCAT, vecchia inquadratura";
        });

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaption()).isNull();
        assertThat(image().isCaptionPending()).as("resta in sospeso: ne scrivera' una nuova il lavoro avviato dal ritaglio").isTrue();
    }

    @Test
    void anImageDeletedWhileTheModelWorksIsNotAnError() {
        when(captioner.caption(any(), anyString(), any())).thenAnswer(call -> {
            store.concurrently(dataset.getId(), d -> d.removeImage(imageId, NOW));
            return "TOKCAT, orfana";
        });

        service.caption(dataset.getId(), imageId);

        assertThat(store.rows.get(dataset.getId()).getImages()).isEmpty();
        verifyNoInteractions(systemEvents);
    }

    @Test
    void aDatasetDeletedWhileTheModelWorksIsNotAnErrorEither() {
        when(captioner.caption(any(), anyString(), any())).thenAnswer(call -> {
            store.rows.remove(dataset.getId());
            return "TOKCAT, orfana";
        });

        service.caption(dataset.getId(), imageId);

        verifyNoInteractions(systemEvents);
    }

    @Test
    void anUnknownOrFrozenDatasetIsANothingToDo() {
        service.caption(999L, 1L);
        service.caption(null, null);
        TrainingDataset frozen = new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, null, NOW);
        frozen.addImage("s.png", "s.png", NOW).requestAutoCaption(false);
        TrainingDataset savedFrozen = store.save(frozen);

        service.caption(savedFrozen.getId(), savedFrozen.getImages().get(0).getId());

        verifyNoInteractions(captioner);
        verifyNoInteractions(systemEvents);
    }

    @Test
    void aRefusalMarksTheImageFailedWithoutASystemEvent() {
        when(captioner.caption(any(), anyString(), any())).thenThrow(new ImageCaptionException("I'm sorry", null));

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaptionStatus()).isEqualTo(CaptionStatus.FAILED);
        verifyNoInteractions(systemEvents);
    }

    @Test
    void aRealFailureMarksTheImageFailedAndIsRecorded() {
        RuntimeException failure = new OpenRouterException("openrouter giu'", null, Kind.TRANSIENT);
        when(captioner.caption(any(), anyString(), any())).thenThrow(failure);

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaptionStatus()).isEqualTo(CaptionStatus.FAILED);
        verify(systemEvents).record("captionTrainingImage", failure, "trainingDataset:" + dataset.getId());
    }

    @Test
    void aMissingFileMarksTheImageFailedWithoutAnEvent() {
        when(storage.read("a.png")).thenThrow(new StorageException("file mancante", null, Kind.REJECTED));

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaptionStatus()).isEqualTo(CaptionStatus.FAILED);
        verifyNoInteractions(captioner);
        verifyNoInteractions(systemEvents);
    }

    @Test
    void anUnexpectedErrorInTheFlowNeverPropagatesOutOfTheBackgroundThread() {
        store.failOnSave = new IllegalStateException("db giu'");
        when(captioner.caption(any(), anyString(), any())).thenReturn("TOKCAT, x");

        service.caption(dataset.getId(), imageId); // non deve lanciare

        verify(systemEvents).record(eq(AppEventSource.TRAINING), eq("captionTrainingImage"), any(IllegalStateException.class),
                eq("trainingDataset:" + dataset.getId()));
    }

    @Test
    void theSameImageIsNeverCaptionedTwiceAtTheSameTime() {
        when(captioner.caption(any(), anyString(), any())).thenAnswer(call -> {
            service.caption(dataset.getId(), imageId); // un secondo evento mentre il primo lavoro e' in corso
            return "TOKCAT, una sola volta";
        });

        service.caption(dataset.getId(), imageId);

        verify(captioner, times(1)).caption(any(), anyString(), any());
        assertThat(image().getCaption()).isEqualTo("TOKCAT, una sola volta");
    }

    @Test
    void aWriteConflictIsRetriedSoTheCaptionStillArrives() {
        when(captioner.caption(any(), anyString(), any())).thenReturn("TOKCAT, dopo il conflitto");
        store.beforeNextSave = () -> store.concurrently(dataset.getId(), d -> d.touch(NOW.plusSeconds(5))); // l'utente salva qualcosa nel frattempo

        service.caption(dataset.getId(), imageId);

        assertThat(image().getCaption()).isEqualTo("TOKCAT, dopo il conflitto");
    }

    @Test
    void recoveryRelaunchesEveryPendingImageOfADraftAndNothingElse() {
        TrainingDataset other = new TrainingDataset("altro", "TOK", LoraType.SUBJECT, null, NOW);
        other.addImage("b.png", "b.png", NOW); // non in sospeso
        store.save(other);

        int restarted = service.recoverPending();

        assertThat(restarted).isEqualTo(1);
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), imageId));
    }
}
