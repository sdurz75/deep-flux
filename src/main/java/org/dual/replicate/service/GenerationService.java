package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.replicate.PredictionResponse;
import org.dual.replicate.replicate.ReplicateClient;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.repository.GenerationRepository;
import org.springframework.stereotype.Service;

/**
 * Orchestrazione della generazione: crea la prediction su Replicate,
 * salva lo stato locale e, su richiesta (polling htmx via
 * GenerationController), fa avanzare lo stato fino al download
 * dell'immagine.
 */
@Service
public class GenerationService {

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final GenerationRepository repository;
    private final ReplicateClient replicateClient;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public GenerationService(GenerationRepository repository,
                              ReplicateClient replicateClient,
                              ImageStorageService imageStorageService,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.replicateClient = replicateClient;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Avvia una nuova generazione. {@code parametersJson}, se presente,
     * deve essere un oggetto JSON valido: i suoi campi vengono uniti al
     * prompt per formare l'input della prediction.
     */
    public Generation create(String model, String version, String prompt, String parametersJson) {
        // I form HTML inviano sempre il campo anche se lasciato vuoto: normalizziamo
        // a null, altrimenti "" viene persistita e i th:if dei template (per cui una
        // stringa vuota e' "vera" in Thymeleaf) la mostrerebbero come fosse valorizzata.
        version = blankToNull(version);
        parametersJson = blankToNull(parametersJson);

        Map<String, Object> input = parseParameters(parametersJson);
        input.put("prompt", prompt);

        PredictionResponse prediction = replicateClient.createPrediction(model, version, input);

        Generation generation = new Generation(prediction.id(), model, version, prompt, parametersJson);
        generation.setStatus(mapStatus(prediction.status()));
        return repository.save(generation);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Fa avanzare lo stato di una generazione non ancora terminale: se la
     * prediction e' pronta scarica l'immagine, se e' fallita registra
     * l'errore, se e' scaduta la marca FAILED per timeout. Nessuna
     * chiamata esterna se lo stato e' gia' terminale.
     */
    public Generation refresh(Long id) {
        Generation generation = get(id);
        if (generation.isTerminal()) {
            return generation;
        }

        PredictionResponse prediction;
        try {
            prediction = replicateClient.getPrediction(generation.getExternalId());
        } catch (Exception e) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage("Errore nel contattare Replicate: " + e.getMessage());
            generation.setCompletedAt(Instant.now());
            return repository.save(generation);
        }

        if (prediction.succeeded()) {
            String outputUrl = prediction.firstOutputUrl();
            if (outputUrl == null) {
                generation.setStatus(GenerationStatus.FAILED);
                generation.setErrorMessage("Prediction completata ma senza output utilizzabile.");
            } else {
                String filename = imageStorageService.downloadAndStore(generation.getId(), outputUrl);
                generation.setImageFilename(filename);
                generation.setStatus(GenerationStatus.SUCCEEDED);
            }
            generation.setCompletedAt(Instant.now());
        } else if (prediction.failed()) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(prediction.error() != null ? prediction.error() : "Generazione fallita su Replicate.");
            generation.setCompletedAt(Instant.now());
        } else if (Duration.between(generation.getCreatedAt(), Instant.now()).compareTo(TIMEOUT) > 0) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage("Timeout: nessuna risposta da Replicate dopo " + TIMEOUT.toMinutes() + " minuti.");
            generation.setCompletedAt(Instant.now());
        } else {
            generation.setStatus(mapStatus(prediction.status()));
        }

        return repository.save(generation);
    }

    public Generation get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ReplicateException("Generazione non trovata: " + id));
    }

    private Map<String, Object> parseParameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(parametersJson, Map.class);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JacksonException e) {
            throw new ReplicateException("I parametri devono essere un oggetto JSON valido: " + e.getOriginalMessage());
        }
    }

    private GenerationStatus mapStatus(String replicateStatus) {
        if (replicateStatus == null) {
            return GenerationStatus.PENDING;
        }
        return switch (replicateStatus) {
            case "starting" -> GenerationStatus.PENDING;
            case "processing" -> GenerationStatus.PROCESSING;
            case "succeeded" -> GenerationStatus.SUCCEEDED;
            case "failed", "canceled" -> GenerationStatus.FAILED;
            default -> GenerationStatus.PENDING;
        };
    }
}
