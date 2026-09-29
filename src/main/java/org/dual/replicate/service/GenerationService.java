package org.dual.replicate.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dual.replicate.domain.event.GenerationImageDeletedEvent;
import org.dual.replicate.domain.event.GenerationsDeletedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationKind;
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

    /** Un video impiega piu' di un'immagine (fino a 20 s di clip): stessa logica di TIMEOUT, soglia piu' larga. */
    private static final Duration VIDEO_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Soglia PER MODELLO oltre la quale create() rifiuta una nuova generazione, vedi TooManyPredictionsException. */
    private static final int MAX_IN_PROGRESS_PREDICTIONS_PER_MODEL = 4;

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

    /**
     * Best-effort: molti modelli Cog (i Flux inclusi) stampano il seed
     * effettivamente usato nei log quando l'utente non ne specifica uno
     * ("Using seed: 12345" o simili) - convenzione comune, non garantita
     * ne' documentata da Replicate, vedi refresh(). "seed" come parola
     * intera, poi fino a 10 caratteri non numerici (label/punteggiatura),
     * poi le cifre.
     */
    private static final Pattern SEED_LOG_PATTERN = Pattern.compile("(?i)\\bseed\\b\\D{0,10}(\\d+)");

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
     *
     * Per le immagini {@code disable_safety_checker} e' sempre forzato a true qui (per i video no, vedi l'overload sotto),
     * qualunque sia il modello o il chiamante (form diretto via
     * GenerationController, tool via ImageGenerationTool): nessuno dei
     * form-type censiti lo espone come campo (vedi
     * generation-params-flux-lora-ff3.html/generation-params-flux-2-klein-9b.html/
     * generation-params-flux-krea-dev.html),
     * quindi l'unico punto in cui puo' essere garantito per OGNI modello
     * censito, presente e futuro, e' qui - non in ciascun
     * GenerationParameterHandler (duplicherebbe la regola una volta per
     * form-type) ne' nel solo chiamante chatbot (lascerebbe il form
     * diretto scoperto, come accadeva prima). Sovrascrive sempre
     * qualunque valore eventualmente presente in parametersJson, non solo
     * quando assente: "sempre true" non e' un default, e' un vincolo.
     */
    public Generation create(String model, String version, String prompt, String parametersJson) {
        return create(model, version, prompt, parametersJson, GenerationKind.IMAGE, null);
    }

    /**
     * Come {@link #create(String, String, String, String)} ma per un
     * {@code kind} esplicito (vedi {@link GenerationKind}) e con l'eventuale
     * generazione sorgente di un img2video. {@code disable_safety_checker}
     * e' un input dei soli modelli immagine censiti: p-video non lo
     * dichiara (ha un suo {@code disable_safety_filter}, gia' true di
     * default), quindi per i video non viene aggiunto.
     */
    public Generation create(String model, String version, String prompt, String parametersJson,
                             GenerationKind kind, Long sourceGenerationId) {
        // I form HTML inviano sempre il campo anche se lasciato vuoto: normalizziamo
        // a null, altrimenti "" viene persistita e i th:if dei template (per cui una
        // stringa vuota e' "vera" in Thymeleaf) la mostrerebbero come fosse valorizzata.
        version = blankToNull(version);
        parametersJson = blankToNull(parametersJson);

        long inProgress = repository.countByModelAndStatusInAndCreatedAtAfter(
                model, List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING), Instant.now().minus(timeoutFor(kind)));
        if (inProgress >= MAX_IN_PROGRESS_PREDICTIONS_PER_MODEL) {
            throw new TooManyPredictionsException(messages.get("generation.error.tooManyInProgress", inProgress));
        }

        Map<String, Object> input = parseParameters(parametersJson);
        input.put("prompt", prompt);
        if (kind == GenerationKind.IMAGE) {
            input.put("disable_safety_checker", true);
        } else if (sourceGenerationId != null) {
            input.put("image", sourceImageDataUri(sourceGenerationId));
        }

        PredictionResponse prediction = replicateClient.createPrediction(model, version, input);
        log.info("Generazione avviata su Replicate: model={}, version={}, externalId={}, status={}",
                model, version, prediction.id(), prediction.status());

        Generation generation = new Generation(prediction.id(), model, version, prompt, parametersJson, seedOf(input));
        generation.setKind(kind);
        generation.setSourceGenerationId(sourceGenerationId);
        generation.setStatus(mapStatus(prediction.status()));
        return repository.save(generation);
    }

    /**
     * Prima immagine della generazione sorgente di un img2video, come
     * data-URI (vedi ImageStorageService#readAsDataUri). Va nell'input
     * Replicate ma MAI in parametersJson: sarebbe un LOB da centinaia di KB
     * persistito e poi stampato nel dettaglio; la sorgente resta tracciata da
     * {@code sourceGenerationId}.
     */
    private String sourceImageDataUri(Long sourceGenerationId) {
        Generation source = repository.findById(sourceGenerationId)
                .orElseThrow(() -> new ReplicateException(messages.get("generation.error.sourceImageMissing")));
        if (source.getImageFilenames().isEmpty()) {
            throw new ReplicateException(messages.get("generation.error.sourceImageMissing"));
        }
        try {
            return imageStorageService.readAsDataUri(source.getImageFilenames().get(0));
        } catch (java.io.UncheckedIOException e) {
            throw new ReplicateException(messages.get("generation.error.sourceImageMissing"));
        }
    }

    private static Duration timeoutFor(GenerationKind kind) {
        return kind == GenerationKind.VIDEO ? VIDEO_TIMEOUT : TIMEOUT;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** Seed esplicitamente sottomesso dall'utente (chiave "seed" dell'input Replicate), null se non specificato. */
    private Long seedOf(Map<String, Object> input) {
        return input.get("seed") instanceof Number number ? number.longValue() : null;
    }

    /** Vedi Javadoc di SEED_LOG_PATTERN: null se i log sono assenti o non contengono un pattern riconoscibile. */
    private Long seedFromLogs(String logs) {
        if (logs == null || logs.isBlank()) {
            return null;
        }
        Matcher matcher = SEED_LOG_PATTERN.matcher(logs);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
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
                if (generation.getSeed() == null) {
                    generation.setSeed(seedFromLogs(prediction.logs()));
                }
            }
            generation.setCompletedAt(Instant.now());
        } else if (prediction.canceled()) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(messages.get("generation.error.canceled"));
            generation.setCompletedAt(Instant.now());
        } else if (prediction.failed()) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(prediction.error() != null ? prediction.error() : messages.get("generation.error.failedGeneric"));
            generation.setCompletedAt(Instant.now());
        } else if (Duration.between(generation.getCreatedAt(), Instant.now()).compareTo(timeoutFor(generation.getKind())) > 0) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(messages.get("generation.error.timeout", timeoutFor(generation.getKind()).toMinutes()));
            generation.setCompletedAt(Instant.now());
        } else {
            generation.setStatus(mapStatus(prediction.status()));
        }

        return saveAndLogIfTerminal(generation);
    }

    /**
     * Interrompe una generazione in corso chiedendo a Replicate di
     * cancellare la prediction, poi fa avanzare lo stato (se Replicate ha
     * gia' risposto "canceled" la generazione diventa FAILED "annullata").
     * Se l'interruzione non riesce (prediction gia' terminale, errore di
     * rete...) la ReplicateException risale al chiamante e la generazione
     * resta com'e': chi la sta guardando attende la fine naturale.
     */
    public Generation cancel(Long id) {
        Generation generation = get(id);
        if (generation.isTerminal()) {
            return generation;
        }
        replicateClient.cancelPrediction(generation.getExternalId());
        return refresh(id);
    }

    /** Associa una generazione avviata da /deep-chat alla sua conversazione (ripristino del placeholder al reload). */
    public void attachToConversation(Long id, Long conversationId) {
        repository.findById(id).ifPresent(generation -> {
            generation.setConversationId(conversationId);
            repository.save(generation);
        });
    }

    /** Generazioni ancora in corso (non scadute) avviate dalla conversazione indicata. */
    public List<Generation> inProgressForConversation(Long conversationId) {
        return repository.findByConversationIdAndStatusInAndCreatedAtAfterOrderByIdAsc(
                conversationId, List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING),
                Instant.now().minus(TIMEOUT));
    }

    /**
     * Salva e, solo se questa chiamata ha portato la generazione a uno
     * stato terminale (non ad ogni poll: refresh() ritorna subito se lo
     * e' gia', quindi qui si passa esattamente nel momento della
     * transizione), traccia l'esito del colloquio con Replicate a INFO
     * (successo) o WARN (fallimento, con l'errore restituito dall'API).
     */
    private Generation saveAndLogIfTerminal(Generation generation) {
        if (generation.isTerminal() && !repository.existsById(generation.getId())) {
            // La riga e' stata cancellata (GenerationController#deleteOne/delete/deleteImage/
            // deleteAll/deleteEverything) mentre questo refresh() era in volo su Replicate: da
            // /generations (vedi CLAUDE.md) anche generazioni non terminali sono ora cancellabili,
            // quindi questa corsa e' possibile (non lo era finche' solo generazioni SUCCEEDED,
            // sempre gia' terminali, erano esposte alla cancellazione). Le immagini appena
            // scaricate vanno comunque ripulite da disco, altrimenti resterebbero orfane - ma
            // niente da salvare, e SOPRATTUTTO niente ritornato al chiamante: un oggetto
            // "SUCCEEDED" con file gia' cancellati sarebbe un fantasma (GenerationController
            // #status lo mostrerebbe come se esistesse ancora, DeepChatGenerationWatcher
            // proverebbe a persisterlo come turno di chat verso un id ormai inesistente).
            // Stessa eccezione/messaggio di get(id): per il chiamante e' indistinguibile da
            // "non trovata", che e' esattamente cio' che e' diventata.
            generation.getImageFilenames().forEach(imageStorageService::delete);
            throw new ReplicateException(messages.get("generation.error.notFound", generation.getId()));
        }
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

    /**
     * Corpo comune di delete/deleteAll/deleteEverything: cancella i file
     * immagine di ogni generazione, poi le righe (SEMPRE via entita'
     * caricate, mai una query bulk come deleteAllInBatch - GENERATION_IMAGE
     * non ha ON DELETE CASCADE, vedi V4__generation_multiple_images.sql, il
     * cascade sulle sue righe funziona solo perche' Hibernate lo gestisce a
     * livello di entity quando la Generation e' caricata), un solo
     * GenerationsDeletedEvent per l'intero batch (mai uno per riga,
     * altrimenti N cancellazioni ravvicinate scatenerebbero N refresh SSE
     * quasi simultanei sulle altre tab).
     */
    private void deleteGenerations(List<Generation> generations) {
        generations.forEach(generation -> generation.getImageFilenames().forEach(imageStorageService::delete));
        List<Long> ids = generations.stream().map(Generation::getId).toList();
        repository.deleteAllById(ids);
        if (!ids.isEmpty()) {
            eventPublisher.publishEvent(new GenerationsDeletedEvent(ids));
        }
    }

    /**
     * Elimina una generazione e tutti i file immagine associati. Usata dal
     * dettaglio di una generazione (unico punto di cancellazione SINGOLA,
     * vedi GenerationController#delete) e, internamente, da deleteImage
     * quando l'immagine cancellata era l'ultima rimasta.
     */
    public void delete(Long id) {
        deleteGenerations(List.of(get(id)));
    }

    /**
     * Cancellazione in blocco (selezione multipla via checkbox nella
     * lista, vedi GenerationController#deleteSelected/GalleryController
     * #deleteSelected). A differenza di delete(id), id sconosciuti vengono
     * ignorati silenziosamente: non c'e' un singolo id "atteso" da
     * validare in un'operazione di gruppo.
     */
    public void deleteAll(List<Long> ids) {
        deleteGenerations(repository.findAllById(ids));
    }

    /**
     * Azione nucleare (GenerationController#deleteAll, "Elimina tutto" in
     * /generations): elimina OGNI generazione esistente, non solo una
     * selezione. Riusa lo stesso deleteGenerations delle altre due varianti
     * sopra, quindi passa comunque per entita' caricate (mai bulk).
     */
    public void deleteEverything() {
        deleteGenerations(repository.findAll());
    }

    /**
     * Elimina una singola immagine di una generazione ancora esistente. Se
     * era l'ultima immagine rimasta, cancella a cascata l'intera
     * generazione (riusa delete(id), stesso GenerationsDeletedEvent di
     * sempre) - una Generation con imageFilenames vuota non ha senso in
     * questa app (vedi CLAUDE.md, Scopo). Altrimenti rimuove solo il file
     * e la sua voce dalla collezione, pubblicando GenerationImageDeletedEvent
     * (la generazione resta, ma chi la sta guardando deve rifare fetch).
     *
     * @return true se la cancellazione e' stata a cascata (l'intera generazione e' sparita)
     */
    public boolean deleteImage(Long generationId, String filename) {
        Generation generation = get(generationId);
        // contains() e' anche la guardia anti path-traversal: filename deve
        // essere uno dei nomi file GIA' registrati per QUESTA generazione,
        // non un path arbitrario passato dal client.
        if (!generation.getImageFilenames().contains(filename)) {
            throw new ReplicateException(messages.get("gallery.error.imageNotFound"));
        }
        if (generation.getImageFilenames().size() == 1) {
            delete(generationId);
            return true;
        }
        imageStorageService.delete(filename);
        List<String> remaining = new ArrayList<>(generation.getImageFilenames());
        remaining.remove(filename);
        generation.setImageFilenames(remaining);
        repository.save(generation);
        eventPublisher.publishEvent(new GenerationImageDeletedEvent(generationId));
        return false;
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
