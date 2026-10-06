package org.dual.replicate.app.training.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.dual.replicate.app.training.domain.CaptionSource;
import org.dual.replicate.app.training.domain.CaptionStatus;
import org.dual.replicate.app.training.domain.LaunchSettings;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.PendingCaption;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.UploadReport;
import org.dual.replicate.app.training.domain.event.CaptionRequestedEvent;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.Paged;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Store in memoria e storage mockato: nessun DB e nessun file. */
class TrainingDatasetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final int MAX_IMAGES = 3;

    private final InMemoryDatasetStore store = new InMemoryDatasetStore();
    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final Messages messages = mock(Messages.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AtomicInteger stored = new AtomicInteger();
    private TrainingDatasetService service;

    @BeforeEach
    void setUp() {
        // Il testo e' la chiave: basta a distinguere i rifiuti. Il suffisso di copia ha un valore vero per provarne il troncamento.
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        when(messages.get("training.dataset.copySuffix")).thenReturn("(copia)");
        when(storage.storeUpload(any(UploadedFile.class))).thenAnswer(i -> "stored-" + stored.incrementAndGet() + ".png");
        when(storage.copy(anyString())).thenAnswer(i -> "copy-of-" + i.getArgument(0));
        service = new TrainingDatasetService(new DatasetEditor(store, messages), store, storage, systemEvents, messages, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), MAX_IMAGES, 100, 4000);
    }

    // --- configurazione -----------------------------------------------------------------------------------------

    @Test
    void createTrimsAndStoresTheConfiguration() {
        TrainingDataset created = service.create("  Il mio gatto  ", " TOKCAT ", LoraType.SUBJECT, "  una nota ");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Il mio gatto");
        assertThat(created.getTriggerWord()).isEqualTo("TOKCAT");
        assertThat(created.getNote()).isEqualTo("una nota");
        assertThat(created.isFrozen()).isFalse();
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void createRejectsWhatCannotBeATrainingConfiguration() {
        assertRejected(() -> service.create(" ", "TOK", LoraType.SUBJECT, null), "training.error.nameRequired");
        assertRejected(() -> service.create("x".repeat(81), "TOK", LoraType.SUBJECT, null), "training.error.nameTooLong");
        assertRejected(() -> service.create("n", "", LoraType.SUBJECT, null), "training.error.triggerWordRequired");
        assertRejected(() -> service.create("n", "x".repeat(41), LoraType.SUBJECT, null), "training.error.triggerWordTooLong");
        assertRejected(() -> service.create("n", "due parole", LoraType.SUBJECT, null), "training.error.triggerWordInvalid");
        assertRejected(() -> service.create("n", "a,b", LoraType.SUBJECT, null), "training.error.triggerWordInvalid");
        assertRejected(() -> service.create("n", "TOK", null, null), "training.error.typeInvalid");
        assertRejected(() -> service.create("n", "TOK", LoraType.STYLE, "x".repeat(501)), "training.error.noteTooLong");
        assertThat(store.rows).isEmpty();
    }

    @Test
    void anEmptyNoteIsNoNote() {
        assertThat(service.create("n", "TOK", LoraType.STYLE, "   ").getNote()).isNull();
    }

    @Test
    void updateChangesTheConfigurationAndKeepsTheImages() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(dataset.getId(), List.of(upload("a.png")));

        TrainingDataset updated = service.update(dataset.getId(), "nuovo", "NEW", LoraType.STYLE, "nota");

        assertThat(updated.getName()).isEqualTo("nuovo");
        assertThat(updated.getLoraType()).isEqualTo(LoraType.STYLE);
        assertThat(updated.getImages()).hasSize(1);
    }

    @Test
    void anUnknownDatasetIsARejectionNotAnInternalError() {
        assertRejected(() -> service.get(99L), "training.error.notFound");
        assertRejected(() -> service.update(99L, "n", "TOK", LoraType.SUBJECT, null), "training.error.notFound");
        assertThat(service.find(null)).isEmpty();
    }

    // --- immagini -----------------------------------------------------------------------------------------------

    @Test
    void addImagesStoresEachFileAndReportsPerFileInArrivalOrder() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);

        UploadReport report = service.addImages(dataset.getId(), List.of(upload("uno.png"), upload("due.png")));

        assertThat(report.acceptedCount()).isEqualTo(2);
        assertThat(report.results()).extracting(UploadReport.Result::originalName).containsExactly("uno.png", "due.png");
        assertThat(report.results()).extracting(UploadReport.Result::imageId).doesNotContainNull();
        TrainingDataset saved = service.get(dataset.getId());
        assertThat(saved.getImages()).extracting(TrainingImage::getFilename).containsExactly("stored-1.png", "stored-2.png");
        assertThat(saved.getImages()).extracting(TrainingImage::getSortOrder).containsExactly(0, 1);
        // Senza ritaglio i due nomi sono lo stesso file: uno solo da possedere.
        assertThat(saved.getImages().get(0).getOriginalFilename()).isEqualTo(saved.getImages().get(0).getFilename());
        assertThat(saved.getImages().get(0).getCaptionSource()).isEqualTo(CaptionSource.NONE);
    }

    @Test
    void addImagesNeverExceedsTheLimitAndTheRestIsRejectedPerFile() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png")));

        UploadReport report = service.addImages(dataset.getId(), List.of(upload("c.png"), upload("d.png")));

        assertThat(report.results()).extracting(UploadReport.Result::accepted).containsExactly(true, false);
        assertThat(report.results().get(1).rejection()).isEqualTo("training.error.tooManyImages");
        assertThat(service.get(dataset.getId()).getImages()).hasSize(MAX_IMAGES);
        verify(storage, org.mockito.Mockito.times(MAX_IMAGES)).storeUpload(any(UploadedFile.class)); // il file oltre il tetto non si salva nemmeno
    }

    @Test
    void aRejectedFileDoesNotStopTheOthersAndIsNotAnEvent() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        when(storage.storeUpload(any(UploadedFile.class)))
                .thenThrow(new StorageException("formato non supportato", null, Kind.REJECTED))
                .thenReturn("stored-ok.png");

        UploadReport report = service.addImages(dataset.getId(), List.of(upload("brutto.txt"), upload("bello.png")));

        assertThat(report.results()).extracting(UploadReport.Result::accepted).containsExactly(false, true);
        assertThat(report.results().get(0).rejection()).isEqualTo("formato non supportato");
        assertThat(service.get(dataset.getId()).getImages()).hasSize(1);
        verify(systemEvents, never()).record(anyString(), any(Throwable.class));
    }

    @Test
    void aRealStorageFailureIsRecordedAndTheUserGetsAGenericMessage() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        StorageException failure = new StorageException("disco pieno", null, Kind.PERMANENT);
        when(storage.storeUpload(any(UploadedFile.class))).thenThrow(failure);

        UploadReport report = service.addImages(dataset.getId(), List.of(upload("a.png")));

        assertThat(report.results().get(0).rejection()).isEqualTo("training.error.saveFailed");
        verify(systemEvents).record("storeTrainingImage", failure, "trainingDataset:" + dataset.getId());
    }

    @Test
    void anEmptyPartIsNotAFileAndIsSkipped() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);

        UploadReport report = service.addImages(dataset.getId(), List.of(UploadedFile.of("", new byte[0])));

        assertThat(report.results()).isEmpty();
        verify(storage, never()).storeUpload(any(UploadedFile.class));
    }

    @Test
    void theDisplayNameLosesAnyPathTheBrowserSent() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);

        service.addImages(dataset.getId(), List.of(upload("C:\\Users\\io\\foto.png"), upload("/home/io/altra.png"), upload(null)));

        assertThat(service.get(dataset.getId()).getImages()).extracting(TrainingImage::getOriginalName)
                .containsExactly("foto.png", "altra.png", "training.upload.unnamed");
    }

    @Test
    void ifTheRowsCannotBeSavedTheFilesJustWrittenAreRemoved() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        store.failOnSave = new IllegalStateException("db giu'");

        assertThatThrownBy(() -> service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png"))))
                .isInstanceOf(IllegalStateException.class);

        verify(storage).delete("stored-1.png");
        verify(storage).delete("stored-2.png");
    }

    @Test
    void aStaleCopyIsAConflictAndDoesNotLeaveOrphanFiles() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        store.failOnSave = new OptimisticLockingFailureException("versione vecchia");

        assertRejected(() -> service.addImages(dataset.getId(), List.of(upload("a.png"))), "training.error.conflict");
        verify(storage).delete("stored-1.png");
    }

    @Test
    void removeImageDropsTheRowThenItsFiles() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        UploadReport report = service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png")));
        Long first = report.results().get(0).imageId();

        service.removeImage(dataset.getId(), first);

        assertThat(service.get(dataset.getId()).getImages()).extracting(TrainingImage::getFilename).containsExactly("stored-2.png");
        verify(storage).delete("stored-1.png");
        verify(storage, never()).delete("stored-2.png");
    }

    @Test
    void removingAnImageOfAnotherDatasetIsRejectedAndDeletesNothing() {
        TrainingDataset mine = service.create("mio", "TOK", LoraType.SUBJECT, null);
        TrainingDataset other = service.create("altro", "TOK", LoraType.SUBJECT, null);
        Long foreign = service.addImages(other.getId(), List.of(upload("a.png"))).results().get(0).imageId();

        assertRejected(() -> service.removeImage(mine.getId(), foreign), "training.error.imageNotFound");

        verify(storage, never()).delete(anyString());
        assertThat(service.get(other.getId()).getImages()).hasSize(1);
    }

    // --- scritture concorrenti ----------------------------------------------------------------------------------

    @Test
    void aWriteThatFindsAStaleCopyIsReappliedOnAFreshOneInsteadOfFailing() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        int before = store.saveAttempts;
        store.conflictsToThrow = DatasetEditor.MAX_ATTEMPTS - 1;

        TrainingDataset updated = service.update(dataset.getId(), "nuovo", "NEW", LoraType.STYLE, null);

        assertThat(updated.getName()).isEqualTo("nuovo");
        assertThat(store.saveAttempts - before).as("due conflitti e poi il salvataggio riuscito").isEqualTo(DatasetEditor.MAX_ATTEMPTS);
    }

    @Test
    void aWriteThatKeepsFindingAStaleCopyIsAConflictOnlyAfterTheAttempts() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        store.conflictsToThrow = DatasetEditor.MAX_ATTEMPTS;

        assertRejected(() -> service.update(dataset.getId(), "nuovo", "NEW", LoraType.STYLE, null), "training.error.conflict");

        assertThat(store.conflictsToThrow).as("ha provato tutti i tentativi, non uno solo").isZero();
        assertThat(service.get(dataset.getId()).getName()).as("la bozza non e' stata modificata dai tentativi falliti").isEqualTo("n");
    }

    @Test
    void anUploadThatNeedsARetryKeepsItsFilesAndAsksForEachCaptionOnce() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        store.conflictsToThrow = 1;

        UploadReport report = service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png")));

        assertThat(report.acceptedCount()).isEqualTo(2);
        verify(storage, never()).delete(anyString());
        report.results().forEach(r -> verify(publisher, times(1)).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), r.imageId())));
    }

    @Test
    void anUploadThatLosesItsRoomToAConcurrentOneIsRejectedAndLeavesNoFiles() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png"))); // 2 su 3: c'e' posto per UNA
        reset(publisher);
        store.conflictsToThrow = 1;
        // Quando il salvataggio trova la copia vecchia, un altro caricamento ha appena occupato l'ultimo posto.
        store.onConflict = () -> store.concurrently(dataset.getId(), d -> d.addImage("concorrente.png", "concorrente", NOW));

        assertRejected(() -> service.addImages(dataset.getId(), List.of(upload("c.png"))), "training.error.tooManyImages");

        verify(storage).delete("stored-3.png");
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    /** Il caso che il blocco ottimistico deve impedire: una scrittura con una copia vecchia che riporta in silenzio una didascalia appena arrivata a com'era. */
    @Test
    void aCaptionThatArrivesBetweenTheReadAndTheSaveOfAnotherWriteIsNotRevertedByIt() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId(); // PENDING
        store.beforeNextSave = () -> store.concurrently(dataset.getId(),
                d -> d.findImage(id).orElseThrow().applyAutoCaption("stored-1.png", "TOK, un gatto"));

        service.update(dataset.getId(), "nuovo", "TOK", LoraType.SUBJECT, null);

        TrainingImage image = store.image(dataset.getId(), id);
        assertThat(image.getCaption()).as("la didascalia arrivata nel frattempo sopravvive").isEqualTo("TOK, un gatto");
        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
        assertThat(store.rows.get(dataset.getId()).getName()).as("e la modifica riesce, riapplicata sullo stato nuovo").isEqualTo("nuovo");
    }

    // --- didascalia automatica richiesta da chi modifica le immagini ----------------------------------------------

    @Test
    void everyNewImageStartsWithItsAutomaticCaptionPendingAndIsAnnounced() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);

        UploadReport report = service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png")));

        assertThat(service.get(dataset.getId()).getImages()).extracting(TrainingImage::getCaptionStatus)
                .containsOnly(CaptionStatus.PENDING);
        report.results().forEach(r -> verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), r.imageId())));
    }

    @Test
    void aRejectedUploadAsksForNoCaption() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        when(storage.storeUpload(any(UploadedFile.class))).thenThrow(new StorageException("tipo non valido", null, Kind.REJECTED));

        service.addImages(dataset.getId(), List.of(upload("a.txt")));

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void croppingAgainAsksForANewAutomaticCaptionBecauseTheFramingChanged() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId();
        store.image(dataset.getId(), id).applyAutoCaption("stored-1.png", "TOK, un gatto"); // arrivata: DONE/AUTO
        reset(publisher);

        TrainingImage image = service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 100, 100).findImage(id).orElseThrow();

        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.PENDING);
        assertThat(image.getCaption()).as("il testo resta finche' non arriva il nuovo: un fallimento non lo perde").isEqualTo("TOK, un gatto");
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), id));
    }

    @Test
    void croppingNeverTouchesACaptionTheUserWrote() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId();
        store.image(dataset.getId(), id).writeCaption("TOK, la mia didascalia");
        reset(publisher);

        TrainingImage image = service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 100, 100).findImage(id).orElseThrow();
        service.resetCrop(dataset.getId(), id);

        assertThat(image.getCaption()).isEqualTo("TOK, la mia didascalia");
        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.DONE);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void resettingTheCropAsksForANewAutomaticCaptionToo() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId();
        service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 100, 100);
        store.image(dataset.getId(), id).applyAutoCaption("stored-2.png", "TOK, ritagliata");
        reset(publisher);

        TrainingImage image = service.resetCrop(dataset.getId(), id).findImage(id).orElseThrow();

        assertThat(image.getCaptionStatus()).isEqualTo(CaptionStatus.PENDING);
        verify(publisher).publishEvent((Object) new CaptionRequestedEvent(dataset.getId(), id));
    }

    // --- ritaglio -----------------------------------------------------------------------------------------------

    @Test
    void cropPutsTheCroppedFileInTheZipSlotKeepsTheOriginalAndRemembersTheRectangle() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId(); // stored-1.png

        TrainingImage image = service.cropImage(dataset.getId(), id, upload("crop.jpg"), 10, 20, 300, 400).findImage(id).orElseThrow();

        assertThat(image.getFilename()).as("e' il file che andra' nello zip").isEqualTo("stored-2.png");
        assertThat(image.getOriginalFilename()).as("l'originale non si tocca").isEqualTo("stored-1.png");
        assertThat(image.isCropped()).isTrue();
        assertThat(List.of(image.getCropX(), image.getCropY(), image.getCropW(), image.getCropH())).containsExactly(10, 20, 300, 400);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void croppingAgainRemovesThePreviousCropButNeverTheOriginal() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId(); // stored-1.png
        service.cropImage(dataset.getId(), id, upload("c1.jpg"), 0, 0, 100, 100); // stored-2.png

        TrainingImage image = service.cropImage(dataset.getId(), id, upload("c2.jpg"), 5, 5, 50, 60).findImage(id).orElseThrow(); // stored-3.png

        assertThat(image.getFilename()).isEqualTo("stored-3.png");
        assertThat(image.getOriginalFilename()).isEqualTo("stored-1.png");
        verify(storage).delete("stored-2.png");
        verify(storage, never()).delete("stored-1.png");
    }

    @Test
    void aRectangleThatCannotBeACropIsRejectedBeforeAnyFileIsStored() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId();
        int storedBefore = stored.get();

        assertRejected(() -> service.cropImage(dataset.getId(), id, upload("c.jpg"), -1, 0, 10, 10), "training.error.cropInvalid");
        assertRejected(() -> service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, -1, 10, 10), "training.error.cropInvalid");
        assertRejected(() -> service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 0, 10), "training.error.cropInvalid");
        assertRejected(() -> service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 10, 0), "training.error.cropInvalid");
        assertRejected(() -> service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, ITrainingDatasets.MAX_CROP_SIDE + 1, 10),
                "training.error.cropInvalid");

        assertThat(stored.get()).as("nessun file salvato dai rifiuti").isEqualTo(storedBefore);
        assertThat(service.get(dataset.getId()).findImage(id).orElseThrow().isCropped()).isFalse();
    }

    @Test
    void cropOfAnImageOfAnotherDatasetIsRejectedAndStoresNothing() {
        TrainingDataset mine = service.create("mio", "TOK", LoraType.SUBJECT, null);
        TrainingDataset other = service.create("altro", "TOK", LoraType.SUBJECT, null);
        Long foreign = service.addImages(other.getId(), List.of(upload("a.png"))).results().get(0).imageId();
        int storedBefore = stored.get();

        assertRejected(() -> service.cropImage(mine.getId(), foreign, upload("c.jpg"), 0, 0, 10, 10), "training.error.imageNotFound");

        assertThat(stored.get()).isEqualTo(storedBefore);
        assertThat(service.get(other.getId()).findImage(foreign).orElseThrow().isCropped()).isFalse();
    }

    @Test
    void ifTheRowCannotBeSavedTheNewCropIsRemovedAndThePreviousOneIsKept() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId(); // stored-1.png
        service.cropImage(dataset.getId(), id, upload("c1.jpg"), 0, 0, 100, 100); // stored-2.png
        store.failOnSave = new IllegalStateException("db giu'");

        assertThatThrownBy(() -> service.cropImage(dataset.getId(), id, upload("c2.jpg"), 1, 1, 50, 50)).isInstanceOf(IllegalStateException.class);

        verify(storage).delete("stored-3.png");
        verify(storage, never()).delete("stored-2.png");
        verify(storage, never()).delete("stored-1.png");
    }

    @Test
    void aStaleCopyOfTheDatasetMakesTheCropAConflictWithoutOrphans() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId();
        store.failOnSave = new OptimisticLockingFailureException("versione vecchia");

        assertRejected(() -> service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 10, 10), "training.error.conflict");

        verify(storage).delete("stored-2.png");
    }

    @Test
    void resetCropGoesBackToTheOriginalAndRemovesTheCroppedFile() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId(); // stored-1.png
        service.cropImage(dataset.getId(), id, upload("c.jpg"), 0, 0, 100, 100); // stored-2.png

        TrainingImage image = service.resetCrop(dataset.getId(), id).findImage(id).orElseThrow();

        assertThat(image.getFilename()).isEqualTo("stored-1.png");
        assertThat(image.isCropped()).isFalse();
        assertThat(image.getCropX()).isNull();
        verify(storage).delete("stored-2.png");
        verify(storage, never()).delete("stored-1.png");
    }

    @Test
    void resetCropOfAnUncroppedImageChangesNothingAndDeletesNothing() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        Long id = service.addImages(dataset.getId(), List.of(upload("a.png"))).results().get(0).imageId();

        TrainingImage image = service.resetCrop(dataset.getId(), id).findImage(id).orElseThrow();

        assertThat(image.getFilename()).isEqualTo("stored-1.png");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aFrozenSnapshotCannotBeCropped() {
        TrainingDataset snapshot = store.save(new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, 7L, NOW));
        TrainingImage image = snapshot.addImage("snap.png", "snap", NOW);
        store.save(snapshot);
        int storedBefore = stored.get();

        assertRejected(() -> service.cropImage(snapshot.getId(), image.getId(), upload("c.jpg"), 0, 0, 10, 10), "training.error.frozen");
        assertRejected(() -> service.resetCrop(snapshot.getId(), image.getId()), "training.error.frozen");

        assertThat(stored.get()).isEqualTo(storedBefore);
    }

    @Test
    void aCroppedImageOwnsBothItsFilesAndAnUncroppedOneOnlyOne() {
        TrainingDataset dataset = new TrainingDataset("n", "TOK", LoraType.SUBJECT, null, NOW);
        TrainingImage plain = dataset.addImage("plain.png", "plain", NOW);
        TrainingImage source = dataset.addImage("original.png", "cropped", NOW);
        TrainingImage cropped = dataset.addCopyOf(source, "cropped.jpg", "original.png", NOW);

        assertThat(plain.ownedFilenames()).containsExactly("plain.png");
        assertThat(cropped.ownedFilenames()).containsExactly("cropped.jpg", "original.png");
        assertThat(dataset.ownedFilenames()).containsExactlyInAnyOrder("plain.png", "original.png", "cropped.jpg");
    }

    // --- clone --------------------------------------------------------------------------------------------------

    @Test
    void duplicateCopiesTheFilesAndKeepsCaptionsAndProvenance() {
        TrainingDataset source = service.create("Gatto", "TOKCAT", LoraType.STYLE, "nota");
        service.addImages(source.getId(), List.of(upload("a.png"), upload("b.png")));
        store.image(source.getId(), service.get(source.getId()).getImages().get(0).getId()).writeCaption("a cat on a sofa");

        TrainingDataset copy = service.duplicate(source.getId());

        assertThat(copy.getId()).isNotEqualTo(source.getId());
        assertThat(copy.getName()).isEqualTo("Gatto (copia)");
        assertThat(copy.getTriggerWord()).isEqualTo("TOKCAT");
        assertThat(copy.getLoraType()).isEqualTo(LoraType.STYLE);
        assertThat(copy.getSourceDatasetId()).isEqualTo(source.getId());
        assertThat(copy.isFrozen()).isFalse();
        assertThat(copy.getImages()).extracting(TrainingImage::getFilename).containsExactly("copy-of-stored-1.png", "copy-of-stored-2.png");
        assertThat(copy.getImages().get(0).getCaption()).isEqualTo("a cat on a sofa");
        assertThat(copy.getImages().get(0).getCaptionSource()).isEqualTo(CaptionSource.MANUAL);
        // Senza ritaglio il file e' uno solo: si copia una volta, non due.
        verify(storage, org.mockito.Mockito.times(2)).copy(anyString());
        assertThat(copy.getImages().get(0).getOriginalFilename()).isEqualTo(copy.getImages().get(0).getFilename());
        // L'originale non cambia.
        assertThat(service.get(source.getId()).getImages()).extracting(TrainingImage::getFilename).containsExactly("stored-1.png", "stored-2.png");
    }

    @Test
    void duplicateTruncatesALongNameInsteadOfExceedingTheLimit() {
        TrainingDataset source = service.create("x".repeat(80), "TOK", LoraType.SUBJECT, null);

        TrainingDataset copy = service.duplicate(source.getId());

        assertThat(copy.getName()).hasSize(80).endsWith(" (copia)");
    }

    @Test
    void ifACopyFailsHalfwayTheFilesAlreadyCopiedAreRemovedAndNoRowIsSaved() {
        TrainingDataset source = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(source.getId(), List.of(upload("a.png"), upload("b.png")));
        when(storage.copy("stored-2.png")).thenThrow(new StorageException("manca", null, Kind.REJECTED));

        assertThatThrownBy(() -> service.duplicate(source.getId())).isInstanceOf(StorageException.class);

        verify(storage).delete("copy-of-stored-1.png");
        assertThat(store.rows).hasSize(1);
    }

    // --- cancellazione ------------------------------------------------------------------------------------------

    @Test
    void deleteRemovesTheRowAndThenEveryFileItOwned() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(dataset.getId(), List.of(upload("a.png"), upload("b.png")));

        service.delete(dataset.getId());

        assertThat(store.rows).isEmpty();
        verify(storage).delete("stored-1.png");
        verify(storage).delete("stored-2.png");
    }

    @Test
    void aFileThatCannotBeDeletedIsRecordedButDoesNotUndoTheDelete() {
        TrainingDataset dataset = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(dataset.getId(), List.of(upload("a.png")));
        StorageException failure = new StorageException("webdav giu'", null, Kind.TRANSIENT);
        doThrow(failure).when(storage).delete("stored-1.png");

        service.delete(dataset.getId());

        assertThat(store.rows).isEmpty();
        verify(systemEvents).record("deleteTrainingFile", failure, "trainingDataset:" + dataset.getId());
    }

    // --- snapshot (congelato) -----------------------------------------------------------------------------------

    @Test
    void aFrozenSnapshotCanBeReadAndClonedButNotChanged() {
        TrainingDataset snapshot = store.save(new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, 7L, NOW));
        TrainingImage image = snapshot.addImage("snap.png", "snap", NOW);
        store.save(snapshot);

        assertRejected(() -> service.update(snapshot.getId(), "x", "TOK", LoraType.SUBJECT, null), "training.error.frozen");
        assertRejected(() -> service.addImages(snapshot.getId(), List.of(upload("a.png"))), "training.error.frozen");
        assertRejected(() -> service.removeImage(snapshot.getId(), image.getId()), "training.error.frozen");
        assertRejected(() -> service.delete(snapshot.getId()), "training.error.frozen");

        TrainingDataset copy = service.duplicate(snapshot.getId());
        assertThat(copy.isFrozen()).as("la copia di uno snapshot e' una bozza modificabile").isFalse();
        assertThat(copy.getSourceDatasetId()).isEqualTo(snapshot.getId());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void thePageListsOnlyDraftsMostRecentlyModifiedFirst() {
        service.create("vecchio", "TOK", LoraType.SUBJECT, null);
        store.save(new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, null, NOW));
        service.create("nuovo", "TOK", LoraType.SUBJECT, null);

        Paged<TrainingDataset> page = service.page(0, 10);

        assertThat(page.content()).extracting(TrainingDataset::getName).containsExactly("nuovo", "vecchio");
    }

    // --- impostazioni di lancio e snapshot ---------------------------------------------------------------------

    @Test
    void aNewDraftStartsWithTheDefaultLaunchSettingsAndTheHuggingFaceCopyOn() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);

        assertThat(draft.launchSettings()).isEqualTo(LaunchSettings.defaults());
        assertThat(draft.isHfPublish()).as("la copia su HuggingFace e' acceso di default").isTrue();
        assertThat(draft.isHfPrivate()).as("e privata di default").isTrue();
        assertThat(draft.getTrainingSteps()).isEqualTo(1000);
    }

    @Test
    void saveLaunchSettingsTrimsAndStoresThemWithoutTouchingTheImages() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(draft.getId(), List.of(upload("a.png")));

        TrainingDataset saved = service.saveLaunchSettings(draft.getId(),
                new LaunchSettings("  gatto  ", 1500, 42L, true, 7L, "  mio-gatto ", false));

        assertThat(saved.launchSettings()).isEqualTo(new LaunchSettings("gatto", 1500, 42L, true, 7L, "mio-gatto", false));
        assertThat(service.get(draft.getId()).getImages()).hasSize(1);
    }

    @Test
    void anEmptyModelNameOrRepoNameIsNoName() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);

        TrainingDataset saved = service.saveLaunchSettings(draft.getId(), new LaunchSettings("  ", 1000, null, false, null, "", true));

        assertThat(saved.getModelName()).isNull();
        assertThat(saved.getHfRepoName()).isNull();
    }

    @Test
    void saveLaunchSettingsRejectsValuesOutsideTheLimits() {
        Long id = service.create("n", "TOK", LoraType.SUBJECT, null).getId();

        assertRejected(() -> service.saveLaunchSettings(id, new LaunchSettings(null, 99, null, true, null, null, true)), "training.error.stepsOutOfRange");
        assertRejected(() -> service.saveLaunchSettings(id, new LaunchSettings(null, 4001, null, true, null, null, true)), "training.error.stepsOutOfRange");
        assertRejected(() -> service.saveLaunchSettings(id, new LaunchSettings(null, 1000, -1L, true, null, null, true)), "training.error.seedInvalid");
        assertRejected(() -> service.saveLaunchSettings(id, new LaunchSettings(null, 1000, Integer.MAX_VALUE + 1L, true, null, null, true)),
                "training.error.seedInvalid");
        assertRejected(() -> service.saveLaunchSettings(id, new LaunchSettings("x".repeat(61), 1000, null, true, null, null, true)),
                "training.error.modelNameTooLong");
        assertRejected(() -> service.saveLaunchSettings(id, new LaunchSettings(null, 1000, null, true, null, "x".repeat(97), true)),
                "training.error.hfRepoNameTooLong");
        assertThat(service.get(id).launchSettings()).as("un rifiuto non cambia nulla").isEqualTo(LaunchSettings.defaults());
    }

    @Test
    void aFrozenDatasetKeepsItsLaunchSettings() {
        TrainingDataset snapshot = store.save(new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, null, NOW));

        assertRejected(() -> service.saveLaunchSettings(snapshot.getId(), LaunchSettings.defaults()), "training.error.frozen");
    }

    @Test
    void snapshotFreezesACopyWithItsOwnFilesAndTheSameSettingsAndLeavesTheDraftAlone() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.STYLE, "nota");
        service.addImages(draft.getId(), List.of(upload("a.png"), upload("b.png")));
        service.saveLaunchSettings(draft.getId(), new LaunchSettings("gatto", 1500, 42L, true, 7L, "repo", false));
        TrainingDataset before = service.get(draft.getId());

        TrainingDataset snapshot = service.snapshot(draft.getId());

        assertThat(snapshot.getId()).isNotEqualTo(draft.getId());
        assertThat(snapshot.isFrozen()).isTrue();
        assertThat(snapshot.getSourceDatasetId()).isEqualTo(draft.getId());
        assertThat(snapshot.getName()).as("lo snapshot ha il nome della bozza, senza suffisso di copia").isEqualTo("n");
        assertThat(snapshot.launchSettings()).isEqualTo(before.launchSettings());
        assertThat(snapshot.getImages()).extracting(TrainingImage::getFilename).allMatch(f -> f.startsWith("copy-of-"));
        assertThat(snapshot.getImages()).extracting(TrainingImage::getFilename)
                .doesNotContainAnyElementsOf(before.getImages().stream().map(TrainingImage::getFilename).toList());
        assertThat(service.get(draft.getId()).getImages()).extracting(TrainingImage::getFilename)
                .containsExactlyElementsOf(before.getImages().stream().map(TrainingImage::getFilename).toList());
        assertThat(service.page(0, 10).content()).as("lo snapshot non e' una bozza").extracting(TrainingDataset::getId).containsExactly(draft.getId());
    }

    @Test
    void snapshotOfACroppedImageCopiesBothFilesButOfAnUncroppedOneOnlyOnce() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(draft.getId(), List.of(upload("a.png"), upload("b.png")));
        Long croppedId = service.get(draft.getId()).getImages().get(0).getId();
        service.cropImage(draft.getId(), croppedId, UploadedFile.of("crop.jpg", new byte[] {9}), 0, 0, 10, 10);
        reset(storage);
        when(storage.copy(anyString())).thenAnswer(i -> "copy-of-" + i.getArgument(0));

        service.snapshot(draft.getId());

        // ritagliata: ritaglio + originale (2 copie); non ritagliata: un solo file (1 copia)
        verify(storage, times(3)).copy(anyString());
    }

    @Test
    void aFailedSnapshotDeletesTheFilesAlreadyCopiedAndSavesNothing() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(draft.getId(), List.of(upload("a.png"), upload("b.png")));
        String second = service.get(draft.getId()).getImages().get(1).getFilename();
        when(storage.copy(second)).thenThrow(new StorageException("disco pieno", null, Kind.PERMANENT));
        int rowsBefore = store.rows.size();

        assertThatThrownBy(() -> service.snapshot(draft.getId())).isInstanceOf(StorageException.class);

        assertThat(store.rows).as("nessuna riga a meta'").hasSize(rowsBefore);
        verify(storage).delete(startsWith("copy-of-"));
    }

    @Test
    void snapshotOfAFrozenDatasetIsRejected() {
        TrainingDataset snapshot = store.save(new TrainingDataset("snap", "TOK", LoraType.SUBJECT, null, true, null, NOW));

        assertRejected(() -> service.snapshot(snapshot.getId()), "training.error.frozen");
    }

    @Test
    void deleteSnapshotRemovesTheRowAndItsFilesButNeverADraft() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.addImages(draft.getId(), List.of(upload("a.png")));
        TrainingDataset snapshot = service.snapshot(draft.getId());
        String snapshotFile = snapshot.getImages().get(0).getFilename();
        reset(storage);

        assertRejected(() -> service.deleteSnapshot(draft.getId()), "training.error.notASnapshot");
        assertThat(store.rows).containsKey(draft.getId());
        verify(storage, never()).delete(anyString());

        service.deleteSnapshot(snapshot.getId());

        assertThat(store.rows).doesNotContainKey(snapshot.getId());
        verify(storage).delete(snapshotFile);
        assertThat(store.rows).as("la bozza resta con i suoi file").containsKey(draft.getId());
    }

    @Test
    void duplicateCarriesTheLaunchSettingsOver() {
        TrainingDataset draft = service.create("n", "TOK", LoraType.SUBJECT, null);
        service.saveLaunchSettings(draft.getId(), new LaunchSettings("gatto", 1500, 42L, false, 7L, "repo", false));

        TrainingDataset copy = service.duplicate(draft.getId());

        assertThat(copy.launchSettings()).isEqualTo(new LaunchSettings("gatto", 1500, 42L, false, 7L, "repo", false));
        assertThat(copy.isFrozen()).isFalse();
    }

    // --- aiuti --------------------------------------------------------------------------------------------------

    private static UploadedFile upload(String name) {
        return UploadedFile.of(name, new byte[] {1, 2, 3});
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        assertThatThrownBy(call).isInstanceOfSatisfying(TrainingException.class, e -> {
            assertThat(e.getMessage()).isEqualTo(messageKey);
            assertThat(e.isReportable()).as("un rifiuto atteso non e' un evento di sistema").isFalse();
        });
    }

    /** Il comportamento che conta del DB: assegna gli id, tiene l'ultimo oggetto salvato e puo' essere fatto fallire. */
}
