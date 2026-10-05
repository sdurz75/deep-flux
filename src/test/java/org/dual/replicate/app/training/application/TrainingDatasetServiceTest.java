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
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.UploadReport;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Store in memoria e storage mockato: nessun DB e nessun file. */
class TrainingDatasetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final int MAX_IMAGES = 3;

    private final InMemoryStore store = new InMemoryStore();
    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final Messages messages = mock(Messages.class);
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
        service = new TrainingDatasetService(store, storage, systemEvents, messages, Clock.fixed(NOW, ZoneOffset.UTC), MAX_IMAGES);
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
        verify(systemEvents).record("storeTrainingImage", failure);
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
        TrainingDataset withCaptions = service.get(source.getId());
        ReflectionTestUtils.setField(withCaptions.getImages().get(0), "caption", "a cat on a sofa");
        ReflectionTestUtils.setField(withCaptions.getImages().get(0), "captionSource", CaptionSource.MANUAL);

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
        verify(systemEvents).record("deleteTrainingFile", failure);
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
    private static final class InMemoryStore implements ITrainingDatasetStore {

        final Map<Long, TrainingDataset> rows = new LinkedHashMap<>();
        RuntimeException failOnSave;
        private long nextId = 1;
        private long nextImageId = 1;

        @Override
        public TrainingDataset save(TrainingDataset dataset) {
            if (failOnSave != null) {
                throw failOnSave;
            }
            if (dataset.getId() == null) {
                ReflectionTestUtils.setField(dataset, "id", nextId++);
            }
            for (TrainingImage image : dataset.getImages()) {
                if (image.getId() == null) {
                    ReflectionTestUtils.setField(image, "id", nextImageId++);
                }
            }
            rows.put(dataset.getId(), dataset);
            return dataset;
        }

        @Override
        public Optional<TrainingDataset> findById(Long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Paged<TrainingDataset> findDraftsPage(int pageIndex, int pageSize) {
            List<TrainingDataset> drafts = new ArrayList<>(rows.values().stream().filter(d -> !d.isFrozen()).toList());
            // Stessa data per tutte (orologio fisso): a parita' di istante vince l'id piu' alto, come nella query vera.
            drafts.sort((a, b) -> b.getId().compareTo(a.getId()));
            return new Paged<>(drafts, pageIndex, pageSize, drafts.size());
        }

        @Override
        public void delete(TrainingDataset dataset) {
            rows.remove(dataset.getId());
        }

        @Override
        public void deleteAll() {
            rows.clear();
        }
    }
}
