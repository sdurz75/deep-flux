package org.dual.replicate.service;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
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
import static org.mockito.ArgumentMatchers.anyString;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createSavesGenerationWithPredictionIdAndMergedInput() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper);

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
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));
        when(replicateClient.getPrediction("pred-1"))
                .thenReturn(new PredictionResponse("pred-1", "succeeded", "https://example.com/out.png", null, null));
        when(imageStorageService.downloadAndStore(any(), anyString())).thenReturn("1.png");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(result.getImageFilename()).isEqualTo("1.png");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void refreshDoesNotCallReplicateWhenAlreadyTerminal() {
        GenerationService service = new GenerationService(repository, replicateClient, imageStorageService, objectMapper);

        Generation generation = new Generation("pred-1", "owner/model", null, "a cat", null);
        generation.setStatus(GenerationStatus.SUCCEEDED);
        when(repository.findById(1L)).thenReturn(Optional.of(generation));

        Generation result = service.refresh(1L);

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        verify(replicateClient, org.mockito.Mockito.never()).getPrediction(anyString());
    }
}
