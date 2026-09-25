package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.i18n.Messages;
import org.dual.replicate.replicate.PredictionResponse;
import org.dual.replicate.replicate.ReplicateClient;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.replicate.TooManyPredictionsException;
import org.dual.replicate.repository.GenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Orchestrazione della generazione: crea la prediction su Replicate,
 * salva lo stato locale e, su richiesta (polling htmx via
 * GenerationController), fa avanzare lo stato fino al download
 * dell'immagine.
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Soglia oltre la quale create() rifiuta una nuova generazione, vedi TooManyPredictionsException. */
    private static final int MAX_IN_PROGRESS_PREDICTIONS = 4;

    /**
     * L'API di Replicate risponde occasionalmente con 503 transitori su
     * GET /predictions/{id} (osservato in pratica, non solo teorico).
     * Senza un ritentativo qui, il primo di questi blip fa fallire
     * definitivamente la generazione: {@link #refresh} tratta qualunque
     * eccezione come terminale, e il polling sincrono del tool di
     * generazione immagini su /deep-chat (waitUntilTerminal) chiama
     * refresh() molte piu' volte in rapida sequenza di quanto farebbe
     * un utente che aggiorna la pagina di stato, aumentando le occasioni
     * di incapparci.
     */
    private static final int GET_PREDICTION_RETRIES = 2;
    private static final Duration GET_PREDICTION_RETRY_BACKOFF = Duration.ofMillis(500);

    private final GenerationRepository repository;
    private final ReplicateClient replicateClient;
    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;
    private final Messages messages;
    private final ApplicationEventPublisher eventPublisher;

    public GenerationService(GenerationRepository repository,
                              ReplicateClient replicateClient,
                              ImageStorageService imageStorageService,
                              ObjectMapper objectMapper,
                              Messages messages,
                              ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.replicateClient = replicateClient;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.eventPublisher = eventPublisher;
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

        int inProgress = replicateClient.countInProgressPredictions(MAX_IN_PROGRESS_PREDICTIONS);
        if (inProgress >= MAX_IN_PROGRESS_PREDICTIONS) {
            throw new TooManyPredictionsException(messages.get("generation.error.tooManyInProgress", inProgress));
        }

        Map<String, Object> input = parseParameters(parametersJson);
        input.put("prompt", prompt);

        PredictionResponse prediction = replicateClient.createPrediction(model, version, input);
        log.info("Generazione avviata su Replicate: model={}, version={}, externalId={}, status={}",
                model, version, prediction.id(), prediction.status());

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
            prediction = getPredictionWithRetry(generation.getExternalId());
        } catch (Exception e) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(messages.get("generation.error.contactFailed", e.getMessage()));
            generation.setCompletedAt(Instant.now());
            return saveAndLogIfTerminal(generation);
        }

        if (prediction.succeeded()) {
            List<String> outputUrls = prediction.outputUrls();
            if (outputUrls.isEmpty()) {
                generation.setStatus(GenerationStatus.FAILED);
                generation.setErrorMessage(messages.get("generation.error.noOutput"));
            } else {
                List<String> filenames = new ArrayList<>();
                for (int i = 0; i < outputUrls.size(); i++) {
                    filenames.add(imageStorageService.downloadAndStore(generation.getId(), i, outputUrls.get(i)));
                }
                generation.setImageFilenames(filenames);
                generation.setStatus(GenerationStatus.SUCCEEDED);
            }
            generation.setCompletedAt(Instant.now());
        } else if (prediction.failed()) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(prediction.error() != null ? prediction.error() : messages.get("generation.error.failedGeneric"));
            generation.setCompletedAt(Instant.now());
        } else if (Duration.between(generation.getCreatedAt(), Instant.now()).compareTo(TIMEOUT) > 0) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(messages.get("generation.error.timeout", TIMEOUT.toMinutes()));
            generation.setCompletedAt(Instant.now());
        } else {
            generation.setStatus(mapStatus(prediction.status()));
        }

        return saveAndLogIfTerminal(generation);
    }

    /**
     * Salva e, solo se questa chiamata ha portato la generazione a uno
     * stato terminale (non ad ogni poll: refresh() ritorna subito se lo
     * e' gia', quindi qui si passa esattamente nel momento della
     * transizione), traccia l'esito del colloquio con Replicate a INFO
     * (successo) o WARN (fallimento, con l'errore restituito dall'API).
     */
    private Generation saveAndLogIfTerminal(Generation generation) {
        Generation saved = repository.save(generation);
        if (!saved.isTerminal()) {
            return saved;
        }
        eventPublisher.publishEvent(new GenerationCompletedEvent(saved));
        if (saved.getStatus() == GenerationStatus.FAILED) {
            log.warn("Generazione fallita: id={}, model={}, externalId={}, error={}",
                    saved.getId(), saved.getModel(), saved.getExternalId(), saved.getErrorMessage());
        } else {
            log.info("Generazione completata: id={}, model={}, externalId={}, files={}",
                    saved.getId(), saved.getModel(), saved.getExternalId(), saved.getImageFilenames());
        }
        return saved;
    }

    /**
     * Blocca fino a che la generazione non e' terminale o scade
     * {@code timeout}, richiamando {@link #refresh} a intervalli. Usato
     * dal tool di generazione immagini su /deep-chat, che deve
     * restituire un risultato sincrono al modello: il polling via htmx
     * (GenerationController) resta il pattern primario per l'uso
     * "normale" dell'app, questo e' l'eccezione per il contesto
     * tool-calling. Se scade il timeout la generazione puo' tornare
     * ancora PENDING/PROCESSING (non e' un errore, il chiamante decide
     * come comunicarlo).
     */
    public Generation waitUntilTerminal(Long id, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Generation generation = refresh(id);
        while (!generation.isTerminal() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            generation = refresh(id);
        }
        return generation;
    }

    private PredictionResponse getPredictionWithRetry(String externalId) {
        RuntimeException lastError;
        int attempt = 0;
        while (true) {
            try {
                return replicateClient.getPrediction(externalId);
            } catch (RuntimeException e) {
                lastError = e;
            }
            attempt++;
            if (attempt > GET_PREDICTION_RETRIES) {
                throw lastError;
            }
            try {
                Thread.sleep(GET_PREDICTION_RETRY_BACKOFF.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw lastError;
            }
        }
    }

    public Generation get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ReplicateException(messages.get("generation.error.notFound", id)));
    }

    /** Elimina una generazione e tutti i file immagine associati. Usata dalla galleria. */
    public void delete(Long id) {
        Generation generation = get(id);
        generation.getImageFilenames().forEach(imageStorageService::delete);
        repository.delete(id);
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
            throw new ReplicateException(messages.get("generation.error.invalidParameters", e.getOriginalMessage()));
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
