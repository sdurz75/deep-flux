package org.dual.replicate.app.generation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.AnalysisStatus;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationOrigin;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.domain.ImportReport;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.replicate.app.generation.domain.event.ImageImportedEvent;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.dual.replicate.app.prompt.domain.ImageAnalysisException;
import org.dual.replicate.app.prompt.domain.ImageDescription;
import org.dual.replicate.app.prompt.port.in.IImageDescriber;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tutto mockato: nessuna rete (ne' modello di visione) e nessun DB. */
class ImportedImageServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final IGenerationStore store = mock(IGenerationStore.class);
    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final IImageDescriber describer = mock(IImageDescriber.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Messages messages = mock(Messages.class);
    private final ImportedImageService service =
            new ImportedImageService(store, storage, describer, systemEvents, events, messages, 2, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void stubs() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        // Come il DB: assegna un id alla prima riga salvata.
        when(store.save(any(Generation.class))).thenAnswer(i -> {
            Generation generation = i.getArgument(0);
            if (generation.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(generation, "id", nextId++);
            }
            return generation;
        });
    }

    private long nextId = 100;

    private static UploadedFile file(String name) {
        return UploadedFile.of(name, new byte[]{1, 2, 3});
    }

    private Generation pendingImported(Long id) {
        Generation generation = Generation.imported("f.png", NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(generation, "id", id);
        when(store.findById(id)).thenReturn(Optional.of(generation));
        return generation;
    }

    @Test
    void importingStoresEachFileCreatesAnImportedRowAndPublishesTheEvents() {
        when(storage.storeUpload(any(UploadedFile.class))).thenReturn("aa.png");

        ImportReport report = service.importImages(List.of(file("foto.png")));

        assertThat(report.acceptedCount()).isEqualTo(1);
        ArgumentCaptor<Generation> saved = ArgumentCaptor.forClass(Generation.class);
        verify(store).save(saved.capture());
        Generation g = saved.getValue();
        assertThat(g.getOrigin()).isEqualTo(GenerationOrigin.IMPORTED);
        assertThat(g.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(g.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(g.getImageFilenames()).containsExactly("aa.png");
        assertThat(g.getModel()).isNull();
        assertThat(report.results().get(0).filename()).isEqualTo("aa.png");
        verify(events).publishEvent(any(GenerationCompletedEvent.class));
        verify(events).publishEvent(any(ImageImportedEvent.class));
    }

    /** Un file non valido e' un esito di QUEL file: gli altri proseguono (e un rifiuto atteso non e' un evento di sistema). */
    @Test
    void aRejectedFileDoesNotStopTheOthersAndEmptyPartsAreSkipped() {
        when(storage.storeUpload(any(UploadedFile.class)))
                .thenThrow(new StorageException("formato non valido", null, Kind.REJECTED))
                .thenReturn("bb.png");

        ImportReport report = service.importImages(List.of(file("a.txt"), UploadedFile.of("", new byte[0]), file("b.png")));

        assertThat(report.results()).hasSize(2);
        assertThat(report.results().get(0).rejection()).isEqualTo("formato non valido");
        assertThat(report.results().get(1).isAccepted()).isTrue();
        verify(systemEvents, never()).record(anyString(), any(Throwable.class));
    }

    @Test
    void filesBeyondTheLimitAreRejectedWithAReasonAndNotStored() {
        when(storage.storeUpload(any(UploadedFile.class))).thenReturn("x.png");

        ImportReport report = service.importImages(List.of(file("1.png"), file("2.png"), file("3.png")));

        assertThat(report.acceptedCount()).isEqualTo(2);
        assertThat(report.results().get(2).rejection()).isEqualTo("import.error.tooManyFiles");
        verify(storage, org.mockito.Mockito.times(2)).storeUpload(any(UploadedFile.class));
    }

    @Test
    void ifTheRowCannotBeSavedTheStoredFileIsRemoved() {
        when(storage.storeUpload(any(UploadedFile.class))).thenReturn("cc.png");
        when(store.save(any(Generation.class))).thenThrow(new IllegalStateException("db giu'"));

        ImportReport report = service.importImages(List.of(file("c.png")));

        assertThat(report.rejectedCount()).isEqualTo(1);
        verify(storage).delete("cc.png");
        verify(systemEvents).record(any(org.dual.replicate.core.events.domain.CoreEventSource.class), anyString(), any(Throwable.class));
        verifyNoInteractions(describer);
    }

    @Test
    void analyzeStoresTheDescriptionAsPromptAndTheTagsThenRefreshesTheIndex() {
        Generation generation = pendingImported(5L);
        when(storage.read("f.png")).thenReturn(new SourceImage(new byte[]{1}, "image/png"));
        when(describer.describe(any(SourceImage.class))).thenReturn(new ImageDescription("Un gatto.", List.of("gatto", "cat")));

        service.analyze(5L);

        assertThat(generation.getAnalysisStatus()).isEqualTo(AnalysisStatus.DONE);
        assertThat(generation.getPrompt()).isEqualTo("Un gatto.");
        assertThat(generation.getAnalysisTagList()).containsExactly("gatto", "cat");
        verify(events).publishEvent(any(GenerationCompletedEvent.class));
    }

    /** Rifiuto/risposta illeggibile: esito atteso, senza evento di sistema; l'immagine resta valida e ritentabile. */
    @Test
    void aRefusedAnalysisFailsTheAnalysisQuietlyAndKeepsTheImage() {
        Generation generation = pendingImported(6L);
        when(storage.read("f.png")).thenReturn(new SourceImage(new byte[]{1}, "image/png"));
        when(describer.describe(any(SourceImage.class))).thenThrow(new ImageAnalysisException("no", null));

        service.analyze(6L);

        assertThat(generation.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(generation.getErrorMessage()).isEqualTo("import.error.analysisRefused");
        verify(systemEvents, never()).record(anyString(), any(Throwable.class), anyString());
    }

    @Test
    void aServiceFailureFailsTheAnalysisAndIsRecorded() {
        Generation generation = pendingImported(7L);
        when(storage.read("f.png")).thenReturn(new SourceImage(new byte[]{1}, "image/png"));
        RuntimeException failure = new org.dual.replicate.app.shared.domain.OpenRouterException("timeout", null, Kind.TRANSIENT);
        when(describer.describe(any(SourceImage.class))).thenThrow(failure);

        service.analyze(7L);

        assertThat(generation.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(generation.getErrorMessage()).isEqualTo("import.error.analysisFailed");
        verify(systemEvents).record(org.mockito.ArgumentMatchers.eq("analyzeImportedImage"), org.mockito.ArgumentMatchers.eq(failure), anyString());
    }

    /** Idempotente: una riga non importata, sparita o gia' analizzata non si rianalizza (listener e sweep possono sovrapporsi). */
    @Test
    void analyzeDoesNothingWhenThereIsNothingToAnalyze() {
        Generation done = pendingImported(8L);
        done.applyAnalysis("gia'", List.of());
        when(store.findById(9L)).thenReturn(Optional.empty());
        Generation generated = new Generation("ext", "a/b", null, "p", null);
        when(store.findById(10L)).thenReturn(Optional.of(generated));

        service.analyze(8L);
        service.analyze(9L);
        service.analyze(10L);
        service.analyze(null);

        verifyNoInteractions(describer);
        verify(store, never()).save(any());
    }

    @Test
    void retryReturnsAFailedAnalysisToPendingAndRelaunchesIt() {
        Generation generation = pendingImported(11L);
        generation.failAnalysis("boom");

        service.retryAnalysis(11L);

        assertThat(generation.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(generation.getErrorMessage()).isNull();
        verify(events).publishEvent(any(ImageImportedEvent.class));
    }

    @Test
    void retryOfAnUnknownOrGeneratedRowIsRejected() {
        when(store.findById(12L)).thenReturn(Optional.empty());
        when(store.findById(13L)).thenReturn(Optional.of(new Generation("ext", "a/b", null, "p", null)));

        assertThatThrownBy(() -> service.retryAnalysis(12L)).isInstanceOf(ReplicateException.class);
        assertThatThrownBy(() -> service.retryAnalysis(13L)).isInstanceOf(ReplicateException.class);
        verifyNoInteractions(events);
    }

    @Test
    void recoveryRelaunchesAllPendingAtStartupAndOnlyOldOnesInTheSweep() {
        Generation a = pendingImported(14L);
        when(store.findByAnalysisStatus(AnalysisStatus.PENDING)).thenReturn(List.of(a));
        when(store.findByAnalysisStatusAndCreatedAtBefore(AnalysisStatus.PENDING, NOW.minus(ImportedImageService.PENDING_GRACE)))
                .thenReturn(List.of());

        assertThat(service.recoverPendingAnalyses(true)).isEqualTo(1);
        assertThat(service.recoverPendingAnalyses(false)).isZero();
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(ImageImportedEvent.class));
    }
}
