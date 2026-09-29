package org.dual.replicate.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.domain.event.GenerationsDeletedEvent;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
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
    private ImageStorageService imageStorageService;

    @Mock
    private Messages messages;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSavesGenerationWithPredictionIdAndMergedInput() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-2", "starting", null, null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create("owner/model", null, "a cat", "{\"disable_safety_checker\": false}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("disable_safety_checker", true);
    }

    @Test
    void createRejectsWhenTooManyPredictionsInProgress() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        when(repository.countByModelAndStatusInAndCreatedAtAfter(eq("owner/model"), any(), any())).thenReturn(4L);
        when(messages.get(eq("generation.error.tooManyInProgress"), any())).thenReturn("troppe generazioni in corso");

        assertThatThrownBy(() -> service.create("owner/model", null, "a cat", null))
                .isInstanceOf(TooManyPredictionsException.class)
                .hasMessage("troppe generazioni in corso");

        verify(replicateClient, never()).createPrediction(anyString(), any(), any());
    }

    @Test
    void createProceedsWhenBelowInProgressThreshold() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null, null));
        when(imageStorageService.downloadAndStore(any(), anyInt(), anyString())).thenReturn("1-0.png");
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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null,
                        "Some setup logs...\nUsing seed: 123456\nGenerating..."));
        when(imageStorageService.downloadAndStore(any(), anyInt(), anyString())).thenReturn("1-0.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getSeed()).isEqualTo(123456L);
    }

    @Test
    void refreshDoesNotOverrideAnExplicitlySetSeedFromLogs() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", "{\"seed\": 42}", 42L);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(repository.existsById(1L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null,
                        "Using seed: 999999"));
        when(imageStorageService.downloadAndStore(any(), anyInt(), anyString())).thenReturn("1-0.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getSeed()).isEqualTo(42L);
    }

    @Test
    void refreshDownloadsAllImagesWhenPredictionHasMultipleOutputs() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        Generation generation = new Generation("pred-5", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 5L);
        when(repository.findById(5L)).thenReturn(Optional.of(generation));
        when(repository.existsById(5L)).thenReturn(true);
        when(replicateClient.getPrediction("pred-5"))
                .thenReturn(new PredictionResponse("pred-5", "succeeded",
                        List.of("https://example.com/out-0.png", "https://example.com/out-1.png"), null, null, null));
        when(imageStorageService.downloadAndStore(any(), eq(0), anyString())).thenReturn("5-0.png");
        when(imageStorageService.downloadAndStore(any(), eq(1), anyString())).thenReturn("5-1.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(5L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getImageFilenames()).containsExactly("5-0.png", "5-1.png");
    }

    @Test
    void refreshDoesNotCallReplicateWhenAlreadyTerminal() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        verify(replicateClient, org.mockito.Mockito.never()).getPrediction(anyString());
    }

    @Test
    void deleteRemovesImageFileAndRepositoryRow() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
    void deleteImageRejectsUnknownFilename() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages, eventPublisher);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        ReflectionTestUtils.setField(generation, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null, null));
        when(imageStorageService.downloadAndStore(any(), anyInt(), anyString())).thenReturn("1-0.png");
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
}
