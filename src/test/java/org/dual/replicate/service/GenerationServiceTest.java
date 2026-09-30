package org.dual.replicate.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.domain.event.GenerationsDeletedEvent;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.service.storage.IImageStorageService;
import org.dual.replicate.domain.GenerationKind;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.PredictionResponse;
import org.dual.replicate.replicate.ReplicateClient;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.replicate.TooManyPredictionsException;
import org.dual.replicate.repository.GenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationServiceTest {

    @Mock
    private GenerationRepository repository;

    @Mock
    private ReplicateClient replicateClient;

    @Mock
    private IImageStorageService imageStorageService;

    @Mock
    private Messages messages;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AppErrorService appErrors;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSavesGenerationWithPredictionIdAndMergedInput() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-1", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("owner/model", null, "a cat", "{\"seed\": 42}");

        assertThat(result.getExternalId()).isEqualTo("pred-1");
        assertThat(result.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.getSeed()).isEqualTo(42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue())
                .containsEntry("prompt", "a cat")
                .containsEntry("seed", 42)
                .containsEntry("disable_safety_checker", true);
    }

    /**
     * disable_safety_checker deve restare true per QUALUNQUE modello,
     * indipendentemente da cosa arriva in parametersJson: non e' un
     * default (assente -> true), e' un vincolo (presente e diverso ->
     * comunque true). Nessuno dei due form-type lo espone come campo
     * (vedi Flux2Klein9bParameterHandler/FluxLoraFf3ParameterHandler),
     * quindi un valore "false" qui potrebbe arrivare solo da un chiamante
     * che bypassa l'UI - create() non deve fidarsene.
     */
    @Test
    void createForcesDisableSafetyCheckerEvenIfExplicitlyFalseInParameters() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-2", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create("owner/model", null, "a cat", "{\"disable_safety_checker\": false}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("disable_safety_checker", true);
    }

    /** Un video non riceve disable_safety_checker (p-video non lo dichiara), ma ricorda kind e sorgente. */
    @Test
    void createForVideoOmitsDisableSafetyCheckerAndRecordsKindAndSource() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation source = new Generation("pred-src", "owner/model", null, "a cat", null);
        source.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("7-0.png")));
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(source));
        when(imageStorageService.readAsDataUri("7-0.png")).thenReturn("data:image/png;base64,AAAA");
        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-v", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("prunaai/p-video", null, "a cat walks", "{\"duration\": 5}",
                GenerationKind.VIDEO, 7L, null);

        assertThat(result.getKind()).isEqualTo(GenerationKind.VIDEO);
        assertThat(result.getSourceGenerationId()).isEqualTo(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("prompt", "a cat walks").doesNotContainKey("disable_safety_checker");
    }

    /** Un refresh SUCCEEDED con metrics salva il costo stimato; senza metrics resta null (mai un numero inventato). */
    @Test
    void refreshStoresEstimatedCostFromMetricsWhenAvailable() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        Generation withMetrics = new Generation("pred-c1", "black-forest-labs/flux-krea-dev", null, "p", null);
        Generation withoutMetrics = new Generation("pred-c2", "black-forest-labs/flux-krea-dev", null, "p", null);
        ReflectionTestUtils.setField(withMetrics, "id", 21L);
        ReflectionTestUtils.setField(withoutMetrics, "id", 22L);
        when(repository.findById(21L)).thenReturn(java.util.Optional.of(withMetrics));
        when(repository.findById(22L)).thenReturn(java.util.Optional.of(withoutMetrics));
        when(repository.existsById(any())).thenReturn(true);
        when(replicateClient.getPrediction("pred-c1")).thenReturn(new PredictionResponse("pred-c1", "succeeded",
                "https://example.com/a.png", null, null, null, Map.of("image_output_count", 2)));
        when(replicateClient.getPrediction("pred-c2")).thenReturn(new PredictionResponse("pred-c2", "succeeded",
                "https://example.com/b.png", null, null, null));
        when(imageStorageService.downloadAndStore(anyString())).thenReturn("x.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.refresh(21L).getCostUsd()).isEqualByComparingTo("0.12");
        assertThat(service.refresh(22L).getCostUsd()).isNull();
    }

    /** img2video: l'immagine sorgente va nell'input Replicate come data-URI, ma NON in parametersJson persistito. */
    @Test
    void createForVideoWithSourceSendsImageButDoesNotPersistItInParametersJson() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        Generation source = new Generation("pred-src", "owner/model", null, "a cat", null);
        source.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("7-0.png")));
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(source));
        when(imageStorageService.readAsDataUri("7-0.png")).thenReturn("data:image/png;base64,AAAA");
        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-v", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("prunaai/p-video", null, "walks", "{\"duration\": 5}", GenerationKind.VIDEO, 7L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("image", "data:image/png;base64,AAAA");
        assertThat(result.getParametersJson()).doesNotContain("image").doesNotContain("base64");
    }

    /** img2video stand-alone: l'upload va nell'input come data-URI, non in parametersJson, ed e' tracciato sulla riga. */
    @Test
    void createForVideoWithUploadSendsImageAndRecordsTheUploadFilename() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        when(imageStorageService.readAsDataUri("upload-x.png")).thenReturn("data:image/png;base64,CCCC");
        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-u", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("prunaai/p-video", null, "walks", "{\"duration\": 5}",
                GenerationKind.VIDEO, 7L, null, "upload-x.png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("image", "data:image/png;base64,CCCC");
        assertThat(result.getSourceUploadFilename()).isEqualTo("upload-x.png");
        assertThat(result.getSourceGenerationId()).isNull();
        assertThat(result.getParametersJson()).doesNotContain("base64");
    }

    /** Se Replicate rifiuta, il file caricato non ha piu' un proprietario: va eliminato. */
    @Test
    void createDeletesTheUploadWhenCreationFails() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        when(imageStorageService.readAsDataUri("upload-y.png")).thenReturn("data:image/png;base64,DDDD");
        when(replicateClient.createPrediction(anyString(), any(), any())).thenThrow(new RuntimeException("boom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create("prunaai/p-video", null, "walks", null,
                GenerationKind.VIDEO, null, null, "upload-y.png")).hasMessage("boom");

        verify(imageStorageService).delete("upload-y.png");
    }

    /** Con piu' immagini la sorgente e' quella scelta sul thumbnail, non la prima. */
    @Test
    void createForVideoUsesTheChosenSourceImage() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        Generation source = new Generation("pred-src", "owner/model", null, "a cat", null);
        source.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("7-0.png", "7-1.png")));
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(source));
        when(imageStorageService.readAsDataUri("7-1.png")).thenReturn("data:image/png;base64,BBBB");
        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-v", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create("prunaai/p-video", null, "walks", null, GenerationKind.VIDEO, 7L, "7-1.png");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("image", "data:image/png;base64,BBBB");
    }

    /** File sorgente illeggibile: errore mostrabile dal form (ReplicateException), non un 500, e nessuna prediction avviata. */
    @Test
    void createForVideoFailsCleanlyWhenTheSourceFileIsUnreadable() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        Generation source = new Generation("pred-src", "owner/model", null, "a cat", null);
        source.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("gone.png")));
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(source));
        when(imageStorageService.readAsDataUri("gone.png"))
                .thenThrow(new java.io.UncheckedIOException("missing", new java.io.IOException("nope")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.create("prunaai/p-video", null, "walks", null, GenerationKind.VIDEO, 7L, null))
                .isInstanceOf(org.dual.replicate.replicate.ReplicateException.class);
        org.mockito.Mockito.verifyNoInteractions(replicateClient);
    }

    /** Una generazione video ancora in corso dopo il timeout delle immagini (5 min) non deve essere marcata FAILED. */
    @Test
    void refreshDoesNotTimeOutAVideoAfterFiveMinutes() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
        Generation generation = new Generation("pred-v", "prunaai/p-video", null, "p", null);
        generation.setKind(GenerationKind.VIDEO);
        ReflectionTestUtils.setField(generation, "id", 5L);
        ReflectionTestUtils.setField(generation, "createdAt", java.time.Instant.now().minus(java.time.Duration.ofMinutes(8)));
        when(repository.findById(5L)).thenReturn(java.util.Optional.of(generation));
        when(replicateClient.getPrediction("pred-v"))
                .thenReturn(new PredictionResponse("pred-v", "processing", null, null, null, null));
        when(repository.existsById(5L)).thenReturn(true);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation refreshed = service.refresh(5L);

        assertThat(refreshed.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    void createRejectsWhenTooManyPredictionsInProgress() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        when(repository.countByModelAndStatusInAndCreatedAtAfter(eq("owner/model"), any(), any())).thenReturn(4L);
        when(messages.get(eq("generation.error.tooManyInProgress"), any())).thenReturn("troppe generazioni in corso");

        assertThatThrownBy(() -> service.create("owner/model", null, "a cat", null))
                .isInstanceOf(TooManyPredictionsException.class)
                .hasMessage("troppe generazioni in corso");

        verify(replicateClient, never()).createPrediction(anyString(), any(), any());
    }

    @Test
    void createProceedsWhenBelowInProgressThreshold() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        when(repository.countByModelAndStatusInAndCreatedAtAfter(eq("owner/model"), any(), any())).thenReturn(3L);
        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-1", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("owner/model", null, "a cat", null);

        assertThat(result.getExternalId()).isEqualTo("pred-1");
        verify(replicateClient).createPrediction(anyString(), any(), any());
    }

    @Test
    void cancelMarksGenerationFailedWhenReplicateReportsCanceled() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.PROCESSING);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(messages.get("generation.error.canceled")).thenReturn("annullata");
        when(replicateClient.cancelPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "canceled", null, null, null, null));
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "canceled", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.cancel(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("annullata");
        verify(replicateClient).cancelPrediction("pred-1");
    }

    @Test
    void cancelLeavesGenerationUntouchedWhenReplicateRefuses() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.PROCESSING);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(replicateClient.cancelPrediction("pred-1"))
                .thenThrow(new org.dual.replicate.replicate.ReplicateException("409"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(org.dual.replicate.replicate.ReplicateException.class);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void refreshDownloadsImageWhenPredictionSucceeded() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null, null));
        when(imageStorageService.downloadAndStore(anyString())).thenReturn("1-0.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getImageFilenames()).containsExactly("1-0.png");
        assertThat(result.getCompletedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(GenerationCompletedEvent.class));
    }

    /**
     * Nessun seed esplicito in create() (parametersJson null): refresh()
     * tenta di recuperarlo dai log della prediction una volta completata
     * (best-effort, convenzione comune ma non garantita nei modelli Cog -
     * vedi Javadoc di Generation.seed), cosi' anche un seed "casuale"
     * scelto da Replicate resta tracciato quando il modello lo logga.
     */
    @Test
    void refreshExtractsSeedFromLogsWhenNotSetExplicitly() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null,
                        "Some setup logs...\nUsing seed: 123456\nGenerating..."));
        when(imageStorageService.downloadAndStore(anyString())).thenReturn("1-0.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getSeed()).isEqualTo(123456L);
    }

    @Test
    void refreshDoesNotOverrideAnExplicitlySetSeedFromLogs() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", "{\"seed\": 42}", 42L);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null,
                        "Using seed: 999999"));
        when(imageStorageService.downloadAndStore(anyString())).thenReturn("1-0.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getSeed()).isEqualTo(42L);
    }

    @Test
    void refreshDownloadsAllImagesWhenPredictionHasMultipleOutputs() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-5", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(generation));
        when(repository.existsById(5L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-5"))
                .thenReturn(new PredictionResponse("pred-5", "succeeded",
                        List.of("https://example.com/out-0.png", "https://example.com/out-1.png"), null, null, null));
        when(imageStorageService.downloadAndStore(anyString())).thenReturn("5-0.png", "5-1.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(5L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getImageFilenames()).containsExactly("5-0.png", "5-1.png");
    }

    @Test
    void refreshDoesNotCallReplicateWhenAlreadyTerminal() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        verify(replicateClient, org.mockito.Mockito.never()).getPrediction(anyString());
    }

    @Test
    void deleteRemovesImageFileAndRepositoryRow() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setImageFilenames(List.of("1-0.png", "1-1.png"));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        service.delete(1L);

        verify(imageStorageService).delete("1-0.png");
        verify(imageStorageService).delete("1-1.png");
        verify(repository).deleteAllById(List.of(1L));
        verify(eventPublisher).publishEvent(new GenerationsDeletedEvent(List.of(1L)));
    }

    /**
     * Cancellazione in blocco (checkbox multiple nella lista/griglia, vedi
     * GenerationController#deleteSelected/GalleryController#deleteSelected):
     * un solo evento per l'intero batch, non uno per riga (vedi Javadoc di
     * GenerationService#deleteAll).
     */
    @Test
    void deleteAllRemovesEveryImageFileAndPublishesOneEvent() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation first = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(first, "id", 1L);
        first.setImageFilenames(List.of("1-0.png"));
        Generation second = new Generation("pred-2", "owner/model", null, "a dog", null);
        ReflectionTestUtils.setField(second, "id", 2L);
        second.setImageFilenames(List.of("2-0.png", "2-1.png"));
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(first, second));

        service.deleteAll(List.of(1L, 2L));

        verify(imageStorageService).delete("1-0.png");
        verify(imageStorageService).delete("2-0.png");
        verify(imageStorageService).delete("2-1.png");
        verify(repository).deleteAllById(List.of(1L, 2L));
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(new GenerationsDeletedEvent(List.of(1L, 2L)));
    }

    @Test
    void deleteAllPublishesNothingWhenNoIdsMatch() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        when(repository.findAllById(List.of(99L))).thenReturn(List.of());

        service.deleteAll(List.of(99L));

        verify(eventPublisher, never()).publishEvent(any());
        verify(repository).deleteAllById(List.of());
    }

    /**
     * Azione nucleare ("Elimina tutto" in /generations, GenerationController
     * #deleteAll): riusa lo stesso deleteGenerations di delete/deleteAll,
     * quindi passa per repository.findAll() (entita' caricate, non una
     * query bulk) e pubblica un solo evento per l'intero archivio.
     */
    @Test
    void deleteEverythingRemovesEveryImageFileAndPublishesOneEvent() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation first = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(first, "id", 1L);
        first.setImageFilenames(List.of("1-0.png"));
        Generation second = new Generation("pred-2", "owner/model", null, "a dog", null);
        ReflectionTestUtils.setField(second, "id", 2L);
        second.setImageFilenames(List.of("2-0.png"));
        when(repository.findAll()).thenReturn(List.of(first, second));

        service.deleteEverything();

        verify(imageStorageService).delete("1-0.png");
        verify(imageStorageService).delete("2-0.png");
        verify(repository).deleteAllById(List.of(1L, 2L));
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(new GenerationsDeletedEvent(List.of(1L, 2L)));
    }

    @Test
    void deleteEverythingPublishesNothingWhenArchiveIsEmpty() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        when(repository.findAll()).thenReturn(List.of());

        service.deleteEverything();

        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Caso normale di deleteImage: non e' l'ultima immagine, resta solo
     * il file e la voce in imageFilenames cancellati, la generazione non
     * viene toccata a livello di riga (nessuna repository.deleteAllById).
     */
    @Test
    void deleteImageOfMultiImageGenerationRemovesOnlyThatFileAndPublishesImageDeletedEvent() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setImageFilenames(List.of("1-0.png", "1-1.png"));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        boolean cascaded = service.deleteImage(1L, "1-0.png");

        assertThat(cascaded).isFalse();
        verify(imageStorageService).delete("1-0.png");
        verify(imageStorageService, never()).delete("1-1.png");
        assertThat(generation.getImageFilenames()).containsExactly("1-1.png");
        verify(repository).save(generation);
        verify(repository, never()).deleteAllById(any());
        verify(eventPublisher).publishEvent(new org.dual.replicate.domain.event.GenerationImageDeletedEvent(1L));
    }

    /**
     * Cascade: era l'ultima immagine, deleteImage delega interamente a
     * delete(id) - riga cancellata, GenerationsDeletedEvent pubblicato
     * (mai GenerationImageDeletedEvent per questo caso).
     */
    @Test
    void deleteImageOfLastImageCascadesToWholeGenerationDelete() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setImageFilenames(List.of("1-0.png"));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        boolean cascaded = service.deleteImage(1L, "1-0.png");

        assertThat(cascaded).isTrue();
        verify(imageStorageService).delete("1-0.png");
        verify(repository).deleteAllById(List.of(1L));
        verify(eventPublisher).publishEvent(new GenerationsDeletedEvent(List.of(1L)));
        verify(eventPublisher, never()).publishEvent(any(org.dual.replicate.domain.event.GenerationImageDeletedEvent.class));
    }

    @Test
    void toggleFavouriteFlipsStateAndRejectsForeignFilename() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setImageFilenames(List.of("1-0.png", "1-1.png"));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(messages.get("gallery.error.imageNotFound")).thenReturn("immagine non trovata");

        assertThat(service.toggleFavourite(1L, "1-1.png")).isTrue();
        assertThat(generation.getFavouriteFilenames()).containsExactly("1-1.png");
        assertThat(service.toggleFavourite(1L, "1-1.png")).isFalse();
        assertThat(generation.getFavouriteFilenames()).isEmpty();

        assertThatThrownBy(() -> service.toggleFavourite(1L, "../etc/passwd"))
                .isInstanceOf(ReplicateException.class);
    }

    @Test
    void deleteImageAlsoDropsItsFavouriteMark() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setImageFilenames(List.of("1-0.png", "1-1.png"));
        generation.setFavouriteFilenames(new java.util.LinkedHashSet<>(List.of("1-0.png", "1-1.png")));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        service.deleteImage(1L, "1-0.png");

        assertThat(generation.getFavouriteFilenames()).containsExactly("1-1.png");
    }

    @Test
    void deleteImageRejectsUnknownFilename() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        generation.setImageFilenames(List.of("1-0.png"));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(messages.get("gallery.error.imageNotFound")).thenReturn("immagine non trovata");

        assertThatThrownBy(() -> service.deleteImage(1L, "does-not-exist.png"))
                .isInstanceOf(ReplicateException.class)
                .hasMessage("immagine non trovata");

        verify(imageStorageService, never()).delete(anyString());
        verify(repository, never()).save(any());
        verify(repository, never()).deleteAllById(any());
    }

    /**
     * Race: la riga e' stata cancellata (da /generations, ora possibile
     * anche per generazioni non terminali, vedi CLAUDE.md) mentre questo
     * refresh() era in volo. Le immagini appena scaricate vanno ripulite
     * da disco, ma non c'e' piu' nulla da salvare - niente resurrezione
     * della riga.
     */
    @Test
    void refreshCleansUpDownloadedImagesWhenGenerationWasDeletedConcurrently() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null, null));
        when(imageStorageService.downloadAndStore(anyString())).thenReturn("1-0.png");
        when(repository.existsById(1L)).thenReturn(false);
        when(messages.get(eq("generation.error.notFound"), any())).thenReturn("generazione non trovata");

        // Niente "fantasma" ritornato: la riga non esiste piu', il chiamante (status()/
        // DeepChatGenerationWatcher) deve trattarla come "non trovata", non come un successo.
        assertThatThrownBy(() -> service.refresh(1L))
                .isInstanceOf(ReplicateException.class)
                .hasMessage("generazione non trovata");

        verify(imageStorageService).delete("1-0.png");
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(GenerationCompletedEvent.class));
    }

    /** Un modello di modifica manda la sorgente sotto input_image (non image) e mantiene disable_safety_checker. */
    @Test
    void createForEditModelSendsSourceAsInputImage() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        Generation source = new Generation("pred-src", "owner/model", null, "a cat", null);
        source.setImageFilenames(new java.util.ArrayList<>(java.util.List.of("7-0.png")));
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(source));
        when(imageStorageService.readAsDataUri("7-0.png")).thenReturn("data:image/png;base64,AAAA");
        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-e", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("black-forest-labs/flux-kontext-dev", null, "make it red", null,
                GenerationKind.IMAGE, 7L, "7-0.png", null, "input_image", true);

        assertThat(result.getKind()).isEqualTo(GenerationKind.IMAGE);
        assertThat(result.getSourceGenerationId()).isEqualTo(7L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("input_image", "data:image/png;base64,AAAA")
                .containsEntry("disable_safety_checker", true).doesNotContainKey("image");
    }

    /** Senza sorgente un modello di modifica non parte: nessuna prediction (nessun costo). */
    @Test
    void createForEditModelWithoutSourceFailsBeforeCallingReplicate() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create("black-forest-labs/flux-kontext-dev", null,
                        "make it red", null, GenerationKind.IMAGE, null, null, null, "input_image", true))
                .isInstanceOf(org.dual.replicate.replicate.ReplicateException.class);
        org.mockito.Mockito.verifyNoInteractions(replicateClient);
    }

    private GenerationService newService() {
        return new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher, appErrors);
    }

    private Generation processing(long id, String externalId) {
        Generation generation = new Generation(externalId, "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.PROCESSING);
        ReflectionTestUtils.setField(generation, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(generation));
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(repository.existsById(id)).thenReturn(true);
        return generation;
    }

    /** Un'interruzione di rete NON fa fallire la generazione: resta in corso, l'errore e' registrato, il prossimo poll riprova. */
    @Test
    void transientPollFailureKeepsGenerationInProgressAndRecordsTheError() {
        Generation generation = processing(1L, "pred-1");
        ReplicateException outage = new ReplicateException("rete giu'", null, true);
        when(replicateClient.getPrediction("pred-1")).thenThrow(outage);

        Generation result = newService().refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        verify(appErrors).record(org.dual.replicate.domain.AppErrorSource.REPLICATE, "getPrediction", outage, 1L, null);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Un errore permanente (token errato, 4xx) fa fallire subito: riprovare non servirebbe. */
    @Test
    void permanentPollFailureFailsTheGeneration() {
        processing(1L, "pred-1");
        when(replicateClient.getPrediction("pred-1")).thenThrow(new ReplicateException("401"));
        when(messages.get(eq("generation.error.contactFailed"), any())).thenReturn("contatto fallito");

        Generation result = newService().refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("contatto fallito");
        verify(appErrors).record(eq(org.dual.replicate.domain.AppErrorSource.REPLICATE), eq("getPrediction"), any(), eq(1L), any());
    }

    /** Anche con il poll che continua a fallire, il timeout di business chiude la generazione (e annulla la prediction). */
    @Test
    void transientPollFailurePastTimeoutFailsTheGenerationAndCancelsThePrediction() {
        Generation generation = processing(1L, "pred-1");
        ReflectionTestUtils.setField(generation, "createdAt", java.time.Instant.now().minus(java.time.Duration.ofMinutes(9)));
        when(replicateClient.getPrediction("pred-1")).thenThrow(new ReplicateException("rete giu'", null, true));
        when(messages.get(eq("generation.error.timeout"), any())).thenReturn("timeout");

        Generation result = newService().refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("timeout");
        verify(replicateClient).cancelPrediction("pred-1");
        verify(eventPublisher).publishEvent(any(GenerationCompletedEvent.class));
    }

    /** Download che fallisce a meta' (2 output, il secondo esplode): FAILED terminale, il primo file gia' scritto viene ripulito. */
    @Test
    void downloadFailureFailsTheGenerationAndCleansPartialFiles() {
        processing(1L, "pred-1");
        when(replicateClient.getPrediction("pred-1")).thenReturn(new PredictionResponse("pred-1", "succeeded",
                List.of("https://example.com/a.png", "https://example.com/b.png"), null, null, null));
        when(imageStorageService.downloadAndStore("https://example.com/a.png")).thenReturn("1-0.png");
        when(imageStorageService.downloadAndStore("https://example.com/b.png"))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("disco pieno")));
        when(messages.get(eq("generation.error.downloadFailed"), any())).thenReturn("download fallito");

        Generation result = newService().refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("download fallito");
        assertThat(result.getImageFilenames()).isEmpty();
        assertThat(result.getCompletedAt()).isNotNull();
        verify(imageStorageService).delete("1-0.png");
        verify(appErrors).record(eq(org.dual.replicate.domain.AppErrorSource.STORAGE), eq("downloadOutput"), any(), eq(1L), any());
        verify(eventPublisher).publishEvent(any(GenerationCompletedEvent.class));
    }

    /** Prediction creata su Replicate ma riga non salvabile: la prediction (fatturata) va annullata, l'errore risale. */
    @Test
    void createCancelsThePredictionWhenTheGenerationCannotBeSaved() {
        when(replicateClient.createPrediction(eq("owner/model"), any(), any()))
                .thenReturn(new PredictionResponse("pred-9", "starting", null, null, null, null));
        when(repository.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("db giu'"));
        when(messages.get(eq("generation.error.persistFailed"), any())).thenReturn("salvataggio fallito");

        assertThatThrownBy(() -> newService().create("owner/model", null, "a cat", null))
                .isInstanceOf(ReplicateException.class)
                .hasMessage("salvataggio fallito");

        verify(replicateClient).cancelPrediction("pred-9");
    }

    /** Cancellare una generazione ancora in corso interrompe anche la prediction su Replicate (altrimenti continua a costare). */
    @Test
    void deletingAnInProgressGenerationCancelsItsPrediction() {
        processing(1L, "pred-1");

        newService().delete(1L);

        verify(replicateClient).cancelPrediction("pred-1");
        verify(repository).deleteAllById(List.of(1L));
    }

    /** Il retry di getPrediction non ritenta un errore permanente. */
    @Test
    void permanentPollFailureIsNotRetried() {
        processing(1L, "pred-1");
        when(replicateClient.getPrediction("pred-1")).thenThrow(new ReplicateException("404"));
        when(messages.get(eq("generation.error.contactFailed"), any())).thenReturn("x");

        newService().refresh(1L);

        verify(replicateClient, org.mockito.Mockito.times(1)).getPrediction("pred-1");
    }

    /** Una prediction gia' terminale nella risposta del POST non viene salvata terminale (nessuno la completerebbe): resta in corso. */
    @Test
    void createKeepsAnAlreadyTerminalPredictionInProgressSoRefreshCanCompleteIt() {
        when(replicateClient.createPrediction(eq("owner/model"), any(), any()))
                .thenReturn(new PredictionResponse("pred-9", "succeeded", "https://example.com/a.png", null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation created = newService().create("owner/model", null, "a cat", null);

        assertThat(created.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
    }

    /** La pulizia dell'upload che fallisce non maschera l'errore vero della creazione. */
    @Test
    void createDoesNotLetACleanupFailureMaskTheOriginalError() {
        ReplicateException original = new ReplicateException("Replicate giu'", null, true);
        when(replicateClient.createPrediction(eq("owner/model"), any(), any())).thenThrow(original);
        org.mockito.Mockito.doThrow(new java.io.UncheckedIOException(new java.io.IOException("disco")))
                .when(imageStorageService).delete("upload-1.png");

        assertThatThrownBy(() -> newService().create("owner/model", null, "a cat", null, GenerationKind.IMAGE,
                null, null, "upload-1.png"))
                .isSameAs(original);
    }

    /** Un fallimento nel ripulire i file gia' scritti non impedisce la transizione a FAILED. */
    @Test
    void downloadCleanupFailureStillFailsTheGeneration() {
        processing(1L, "pred-1");
        when(replicateClient.getPrediction("pred-1")).thenReturn(new PredictionResponse("pred-1", "succeeded",
                List.of("https://example.com/a.png", "https://example.com/b.png"), null, null, null));
        when(imageStorageService.downloadAndStore("https://example.com/a.png")).thenReturn("1-0.png");
        when(imageStorageService.downloadAndStore("https://example.com/b.png"))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("disco pieno")));
        org.mockito.Mockito.doThrow(new java.io.UncheckedIOException(new java.io.IOException("non cancellabile")))
                .when(imageStorageService).delete("1-0.png");
        when(messages.get(eq("generation.error.downloadFailed"), any())).thenReturn("download fallito");

        assertThat(newService().refresh(1L).getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    /** Un poll ancora in corso su una riga cancellata nel frattempo non la ri-salva (sarebbe un fantasma). */
    @Test
    void nonTerminalPollOnADeletedRowThrowsNotFoundInsteadOfResavingIt() {
        processing(1L, "pred-1");
        when(repository.existsById(1L)).thenReturn(false);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "processing", null, null, null, null));
        when(messages.get(eq("generation.error.notFound"), any())).thenReturn("non trovata");

        assertThatThrownBy(() -> newService().refresh(1L)).isInstanceOf(ReplicateException.class).hasMessage("non trovata");
        verify(repository, never()).save(any());
    }

    /** Un file che non si lascia cancellare non interrompe il batch: la riga e' comunque eliminata e l'errore registrato. */
    @Test
    void deleteContinuesWhenAFileCannotBeRemoved() {
        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        generation.setImageFilenames(List.of("1-0.png"));
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        RuntimeException locked = new java.io.UncheckedIOException(new java.io.IOException("bloccato"));
        org.mockito.Mockito.doThrow(locked).when(imageStorageService).delete("1-0.png");

        newService().delete(1L);

        verify(repository).deleteAllById(List.of(1L));
        verify(appErrors).record(org.dual.replicate.domain.AppErrorSource.STORAGE, "deleteFile", locked, 1L, null);
    }
}
