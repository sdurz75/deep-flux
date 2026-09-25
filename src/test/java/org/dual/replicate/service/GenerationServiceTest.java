package org.dual.replicate.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.PredictionResponse;
import org.dual.replicate.replicate.ReplicateClient;
import org.dual.replicate.repository.GenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSavesGenerationWithPredictionIdAndMergedInput() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages);

        when(replicateClient.createPrediction(anyString(), any(), any()))
                .thenReturn(new PredictionResponse("pred-1", "starting", null, null, null));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.create("owner/model", null, "a cat", "{\"seed\": 42}");

        assertThat(result.getExternalId()).isEqualTo("pred-1");
        assertThat(result.getStatus()).isEqualTo(GenerationStatus.PENDING);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(replicateClient).createPrediction(anyString(), any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).containsEntry("prompt", "a cat").containsEntry("seed", 42);
    }

    @Test
    void refreshDownloadsImageWhenPredictionSucceeded() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null));
        when(imageStorageService.downloadAndStore(any(), anyInt(), anyString())).thenReturn("1-0.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getImageFilenames()).containsExactly("1-0.png");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void refreshDownloadsAllImagesWhenPredictionHasMultipleOutputs() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages);

        Generation generation = new Generation("pred-5", "owner/model", null, "a cat", null);
        when(repository.findById(5L)).thenReturn(Optional.of(generation));
        when(replicateClient.getPrediction("pred-5"))
                .thenReturn(new PredictionResponse("pred-5", "succeeded",
                        List.of("https://example.com/out-0.png", "https://example.com/out-1.png"), null, null));
        when(imageStorageService.downloadAndStore(any(), eq(0), anyString())).thenReturn("5-0.png");
        when(imageStorageService.downloadAndStore(any(), eq(1), anyString())).thenReturn("5-1.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(5L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getImageFilenames()).containsExactly("5-0.png", "5-1.png");
    }

    @Test
    void refreshDoesNotCallReplicateWhenAlreadyTerminal() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        verify(replicateClient, org.mockito.Mockito.never()).getPrediction(anyString());
    }

    @Test
    void deleteRemovesImageFileAndRepositoryRow() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper, messages);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setImageFilenames(List.of("1-0.png", "1-1.png"));
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        service.delete(1L);

        verify(imageStorageService).delete("1-0.png");
        verify(imageStorageService).delete("1-1.png");
        verify(repository).delete(1L);
    }
}
