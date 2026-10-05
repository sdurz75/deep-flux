package org.dual.replicate.app.generation.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.GenerationFile;
import org.dual.replicate.app.generation.domain.Prediction;
import org.dual.replicate.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.dual.replicate.app.generation.port.out.IPredictionGateway;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.app.generation.application.TokenInputResolver;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.app.generation.domain.event.GenerationFavouriteToggledEvent;
import org.dual.replicate.app.generation.domain.event.GenerationImageDeletedEvent;
import org.dual.replicate.app.generation.domain.event.GenerationsDeletedEvent;
import org.dual.replicate.core.kernel.Paged;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationConfig;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.GenerationOrigin;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.app.generation.domain.ReplicatePricing;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.dual.replicate.app.generation.domain.TooManyPredictionsException;
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
public class GenerationService implements IGenerations {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** Chiave dell'immagine sorgente quando il chiamante non ne specifica una (p-video, il primo modello con sorgente). */
    private static final String DEFAULT_SOURCE_IMAGE_PARAM = "image";
    /** safety_tolerance di flux-fill-pro va da 1 (rigido) a 6 (permissivo): come disable_safety_checker, l'app usa sempre il piu' permissivo. */
    private static final int MOST_PERMISSIVE_SAFETY_TOLERANCE = 6;

    /** Un video impiega piu' di un'immagine (fino a 20 s di clip): stessa logica di TIMEOUT, soglia piu' larga. */
    private static final Duration VIDEO_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Soglia PER MODELLO oltre la quale create() rifiuta una nuova generazione, vedi TooManyPredictionsException. */
    private static final int MAX_IN_PROGRESS_PREDICTIONS_PER_MODEL = 4;

    /**
     * Best-effort: molti modelli Cog (i Flux inclusi) stampano il seed
     * effettivamente usato nei log quando l'utente non ne specifica uno
     * ("Using seed: 12345" o simili) - convenzione comune, non garantita
     * ne' documentata da Replicate, vedi refresh(). "seed" come parola
     * intera, poi fino a 10 caratteri non numerici (label/punteggiatura),
     * poi le cifre.
     */
    private static final Pattern SEED_LOG_PATTERN = Pattern.compile("(?i)\\bseed\\b\\D{0,10}(\\d+)");

    /** Lock a strisce per generazione (id % N): nessuna mappa che cresce, collisioni innocue (solo serializzazione in piu'). */
    private static final Object[] REFRESH_LOCKS = java.util.stream.Stream.generate(Object::new).limit(64).toArray();

    private final IGenerationStore repository;
    private final IPredictionGateway replicateClient;
    private final IImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;
    private final Messages messages;
    private final ApplicationEventPublisher eventPublisher;
    private final ISystemEvents systemEvents;
    private final TokenInputResolver apiTokens;
    private final IModelCatalog modelCatalog;

    public GenerationService(IGenerationStore repository,
                              IPredictionGateway replicateClient,
                              IImageStorageService imageStorageService,
                              ObjectMapper objectMapper,
                              Messages messages,
                              ApplicationEventPublisher eventPublisher,
                              ISystemEvents systemEvents,
                              TokenInputResolver apiTokens,
                              IModelCatalog modelCatalog) {
        this.apiTokens = apiTokens;
        this.modelCatalog = modelCatalog;
        this.repository = repository;
        this.replicateClient = replicateClient;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.eventPublisher = eventPublisher;
        this.systemEvents = systemEvents;
    }

    /**
     * Avvia una nuova generazione. {@code command.parameters()} (valori tipizzati, vocabolario del provider) vengono uniti al
     * prompt per formare l'input della prediction; kind, chiave dell'immagine sorgente e obbligo della sorgente li ricava il
     * form-type del modello (catalogo), non il chiamante.
     *
     * Per le immagini {@code disable_safety_checker} e' sempre forzato a true qui (per i video no: p-video non lo dichiara, ha un
     * suo {@code disable_safety_filter} gia' true di default), qualunque sia il modello o il chiamante (form diretto via
     * GenerationController, tool via ImageGenerationTool): nessuno dei form-type censiti lo espone come campo, quindi l'unico
     * punto in cui puo' essere garantito per OGNI modello censito, presente e futuro, e' qui - non in ciascun handler
     * dei parametri (duplicherebbe la regola una volta per form-type) ne' nel solo chiamante chatbot (lascerebbe il form
     * diretto scoperto). Sovrascrive sempre qualunque valore presente nei parametri, non solo quando assente: "sempre true"
     * non e' un default, e' un vincolo.
     *
     * Un'immagine sorgente (upload, che ha la precedenza, o una generazione immagine riuscita) conta solo per i modelli che ne prendono
     * una ({@link GenerationFormType#takesSourceImage()}): per gli altri e' ignorata (un upload non viene nemmeno salvato). Se la
     * creazione fallisce il file caricato viene eliminato.
     */
    @Override
    public Generation create(CreateCommand command) {
        String storedUpload = null;
        String storedMask = null;
        try {
            storedUpload = storeSourceUpload(command);
            storedMask = storeMaskUpload(command);
            return doCreate(command, storedUpload, storedMask);
        } catch (RuntimeException e) {
            deleteQuietly(storedUpload, e);
            deleteQuietly(storedMask, e);
            throw e;
        }
    }

    /** Elimina un file salvato per una creazione fallita; un file che non si lascia cancellare NON maschera l'errore vero. */
    private void deleteQuietly(String filename, RuntimeException cause) {
        if (filename == null) {
            return;
        }
        try {
            imageStorageService.delete(filename);
        } catch (RuntimeException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public Optional<Generation> findAnimatableSource(Long id, String image) {
        return id == null || image == null ? Optional.empty()
                : repository.findById(id).filter(g -> isAnimatable(g) && g.getImageFilenames().contains(image));
    }

    @Override
    public Optional<GenerationConfig> reuseConfig(Long id, String file) {
        if (id == null) {
            return Optional.empty();
        }
        // Un'immagine importata non e' stata prodotta da una configurazione: niente da riproporre.
        return repository.findById(id).filter(generation -> !generation.isImported()).map(generation -> {
            boolean ofFile = file != null && generation.getImageFilenames().contains(file);
            Map<String, Object> parameters = storedParameters(generation);
            if (ofFile && parameters.containsKey("num_outputs")) {
                parameters.put("num_outputs", 1);
            }
            String seedFile = ofFile ? file : generation.getImageFilenames().stream().findFirst().orElse(null);
            Long seed = seedFile == null ? null : generation.reusableSeedOf(seedFile);
            Long sourceId = generation.getSourceGenerationId();
            String sourceImage = generation.getSourceImageFilename();
            boolean sourceValid = findAnimatableSource(sourceId, sourceImage).isPresent();
            return new GenerationConfig(generation.getModel(), generation.getPrompt(), seed, parameters,
                    sourceValid ? sourceId : null, sourceValid ? sourceImage : null);
        });
    }

    /** I parametri salvati (vocabolario del provider); assenti o illeggibili = nessuno, mai un errore. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> storedParameters(Generation generation) {
        String json = generation.getParametersJson();
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, Map.class));
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    /** Un'immagine riuscita: l'unica generazione che puo' fare da sorgente (animazione, modifica). */
    private static boolean isAnimatable(Generation generation) {
        return generation.getKind() == GenerationKind.IMAGE && generation.getStatus() == GenerationStatus.SUCCEEDED;
    }

    /**
     * Salva l'upload sorgente, ma solo se il modello ne prende una e il file non e' vuoto: per gli altri modelli non viene mai
     * scritto (nessuna {@code Generation} lo possiederebbe).
     */
    private String storeSourceUpload(CreateCommand command) {
        UploadedFile upload = command.sourceUpload();
        if (upload == null || upload.size() == 0) {
            return null;
        }
        boolean takesSource = modelCatalog.formTypeOf(command.model()).map(GenerationFormType::takesSourceImage).orElse(false);
        return takesSource ? imageStorageService.storeUpload(upload) : null;
    }

    /** Salva la maschera di inpainting, ma solo se il modello ne prende una e il file non e' vuoto (come {@link #storeSourceUpload}). */
    private String storeMaskUpload(CreateCommand command) {
        UploadedFile mask = command.maskUpload();
        if (mask == null || mask.size() == 0) {
            return null;
        }
        boolean takesMask = modelCatalog.formTypeOf(command.model()).map(GenerationFormType::takesMask).orElse(false);
        return takesMask ? imageStorageService.storeUpload(mask) : null;
    }

    private Generation doCreate(CreateCommand command, String storedUpload, String storedMask) {
        String model = command.model();
        String prompt = command.prompt();
        // I form HTML inviano sempre il campo anche se lasciato vuoto: normalizziamo
        // a null, altrimenti "" viene persistita e i th:if dei template (per cui una
        // stringa vuota e' "vera" in Thymeleaf) la mostrerebbero come fosse valorizzata.
        String version = blankToNull(command.version());

        // Modello non censito (non dovrebbe succedere: i chiamanti lo scelgono dal catalogo): immagine text-to-image.
        GenerationFormType formType = modelCatalog.formTypeOf(model).orElse(null);
        GenerationKind kind = formType == null ? GenerationKind.IMAGE : formType.kind();
        boolean takesSource = formType != null && formType.takesSourceImage();
        String sourceImageParam = takesSource ? formType.sourceImageParam() : DEFAULT_SOURCE_IMAGE_PARAM;
        boolean sourceRequired = formType != null && formType.sourceRequired();
        String sourceUploadFilename = storedUpload;
        Long sourceGenerationId = takesSource ? command.sourceGenerationId() : null;
        String sourceImage = takesSource ? command.sourceImage() : null;

        long inProgress = repository.countByModelAndStatusInAndCreatedAtAfter(
                model, List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING), Instant.now().minus(timeoutFor(kind)));
        if (inProgress >= MAX_IN_PROGRESS_PREDICTIONS_PER_MODEL) {
            throw new TooManyPredictionsException(messages.get("generation.error.tooManyInProgress", inProgress));
        }

        Map<String, Object> parameters = new LinkedHashMap<>(command.parameters() == null ? Map.of() : command.parameters());
        // Con un'immagine in input p-video ignora aspect_ratio: non lo si invia (ne' lo si salva). kontext-dev lo onora.
        if (kind == GenerationKind.VIDEO && (sourceUploadFilename != null || sourceGenerationId != null)) {
            parameters.remove("aspect_ratio");
        }
        // Salvato PRIMA di risolvere i token: in DB restano gli ID scelti, mai il token in chiaro. Vuoto = null (niente "Parametri: {}").
        String parametersJson = parameters.isEmpty() ? null : toJson(parameters);
        Map<String, Object> input = new LinkedHashMap<>(parameters);
        // Gli ID dei token scelti (hf_token_id/civitai_token_id) diventano il token in chiaro SOLO nell'input per Replicate:
        // parametersJson (salvato sotto) conserva gli ID. Un token inesistente/scaduto lancia un rifiuto PRIMA di spendere nulla.
        apiTokens.resolveInto(input);
        input.put("prompt", prompt);
        if (kind == GenerationKind.IMAGE && formType != null && formType.safetyToleranceParam() != null) {
            // Il modello non ha disable_safety_checker ma una tolleranza 1-6: sempre la piu' permissiva, mai esposta all'utente.
            input.put(formType.safetyToleranceParam(), MOST_PERMISSIVE_SAFETY_TOLERANCE);
        }
        if (kind == GenerationKind.IMAGE && (formType == null || formType.hasDisableSafetyChecker())) {
            input.put("disable_safety_checker", true);
        }
        // Sorgente: un'immagine caricata ha la precedenza su quella di una generazione. I modelli
        // text-to-image la ignorano (gia' azzerata sopra), i video la usano se presente, i
        // modelli di modifica la pretendono.
        if (sourceUploadFilename != null) {
            input.put(sourceImageParam, imageStorageService.readAsDataUri(sourceUploadFilename));
            sourceGenerationId = null;
        } else if (sourceGenerationId != null) {
            input.put(sourceImageParam, sourceImageDataUri(sourceGenerationId, sourceImage));
        } else if (sourceRequired) {
            throw new ReplicateException(messages.get("generation.error.sourceImageRequired"));
        }
        // Inpainting: per i modelli di inpainting puri senza maschera il modello ridipingerebbe a caso, quindi e' un rifiuto prima di
        // spendere nulla. Dove la maschera e' opzionale (flux-lora-finetune) senza e' una normale generazione, ma una maschera senza
        // sorgente non ha niente da mascherare: rifiuto anche qui.
        if (formType != null && formType.takesMask()) {
            if (storedMask == null) {
                if (formType.maskRequired()) {
                    throw new ReplicateException(messages.get("generation.error.maskRequired"));
                }
            } else {
                if (!input.containsKey(sourceImageParam)) {
                    throw new ReplicateException(messages.get("generation.error.maskNeedsSource"));
                }
                input.put(formType.maskParam(), imageStorageService.readAsDataUri(storedMask));
            }
        }

        Prediction prediction = replicateClient.createPrediction(model, version, input);
        log.info("Generazione avviata su Replicate: model={}, version={}, externalId={}, status={}",
                model, version, prediction.id(), prediction.status());

        // Da qui la prediction ESISTE (e viene fatturata) su Replicate: se non riusciamo a tracciarla
        // localmente (id assente, DB in errore) nessuno la vedrebbe mai, quindi la si annulla al meglio
        // prima di propagare l'errore.
        try {
            if (prediction.id() == null || prediction.id().isBlank()) {
                throw new ReplicateException(messages.get("replicate.error.emptyResponse"), null, ReplicateException.Kind.PERMANENT);
            }
            Generation generation = new Generation(prediction.id(), model, version, prompt, parametersJson, seedOf(input));
            generation.setKind(kind);
            generation.setSourceGenerationId(sourceGenerationId);
            generation.setSourceUploadFilename(sourceUploadFilename);
            // Il file preciso scelto fra quelli della generazione sorgente (null se la sorgente e' un upload: sourceGenerationId e' gia' azzerato).
            generation.setSourceImageFilename(sourceGenerationId != null ? sourceImage : null);
            generation.setMaskUploadFilename(storedMask);
            // Una prediction gia' terminale alla risposta del POST (cache, fallimento immediato) NON va salvata
            // terminale: senza download ne' errorMessage nessuno la completerebbe mai (refresh() salta le righe
            // terminali). La si tiene in corso: il primo refresh() legge l'esito vero e lo chiude come le altre.
            GenerationStatus initial = mapStatus(prediction.status());
            generation.setStatus(initial == GenerationStatus.SUCCEEDED || initial == GenerationStatus.FAILED
                    ? GenerationStatus.PROCESSING : initial);
            return repository.save(generation);
        } catch (RuntimeException e) {
            cancelPredictionQuietly(prediction.id(), null, null);
            if (e instanceof ReplicateException) {
                throw e;
            }
            throw new ReplicateException(messages.get("generation.error.persistFailed", e.getMessage()), e);
        }
    }

    /**
     * Prima immagine della generazione sorgente di un img2video, come
     * data-URI (vedi IImageStorageService#readAsDataUri). Va nell'input
     * Replicate ma MAI in parametersJson: sarebbe un LOB da centinaia di KB
     * persistito e poi stampato nel dettaglio; la sorgente resta tracciata da
     * {@code sourceGenerationId}.
     */
    private String sourceImageDataUri(Long sourceGenerationId, String sourceImage) {
        Generation source = repository.findById(sourceGenerationId)
                .filter(GenerationService::isAnimatable)
                .orElseThrow(() -> new ReplicateException(messages.get("generation.error.sourceImageMissing")));
        // L'immagine esatta scelta dall'utente sul thumbnail; senza (null) la prima, per compatibilita'.
        String filename = sourceImage != null ? sourceImage
                : source.getImageFilenames().isEmpty() ? null : source.getImageFilenames().get(0);
        if (filename == null || !source.getImageFilenames().contains(filename)) {
            throw new ReplicateException(messages.get("generation.error.sourceImageMissing"));
        }
        try {
            return imageStorageService.readAsDataUri(filename);
        } catch (StorageException e) {
            if (e.kind() == RemoteServiceException.Kind.REJECTED) { // file inesistente: sorgente mancante
                throw new ReplicateException(messages.get("generation.error.sourceImageMissing"));
            }
            throw e; // guasto vero dello storage (WebDAV giu'...): non e' "sorgente mancante", va registrato
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

    /** Vedi Javadoc di SEED_LOG_PATTERN: tutti i seed riconosciuti nei log, in ordine (vuota se assenti). */
    private List<Long> seedsFromLogs(String logs) {
        List<Long> seeds = new ArrayList<>();
        if (logs == null || logs.isBlank()) {
            return seeds;
        }
        Matcher matcher = SEED_LOG_PATTERN.matcher(logs);
        while (matcher.find()) {
            try {
                seeds.add(Long.valueOf(matcher.group(1)));
            } catch (NumberFormatException e) {
                // Best-effort: un "seed" enorme nei log (oltre Long) non deve far fallire una generazione riuscita.
                // Il conteggio non torna piu' ai file: nessun abbinamento per file (vedi refresh).
                seeds.add(null);
            }
        }
        return seeds;
    }

    /**
     * Fa avanzare lo stato di una generazione non ancora terminale: se la
     * prediction e' pronta scarica l'immagine, se e' fallita registra
     * l'errore, se e' scaduta la marca FAILED per timeout. Nessuna
     * chiamata esterna se lo stato e' gia' terminale.
     */
    @Override
    public Generation refresh(Long id) {
        // Un solo refresh alla volta per generazione (poller htmx, watcher, sweep di recupero possono incrociarsi):
        // senza, due download degli stessi output si sovrascrivono a vicenda e il perdente, fallendo, cancellerebbe
        // i file del vincitore. Il secondo chiamante rilegge la riga dopo il lock e la trova gia' terminale.
        synchronized (REFRESH_LOCKS[(int) (Math.abs(id) % REFRESH_LOCKS.length)]) {
            return doRefresh(id);
        }
    }

    private Generation doRefresh(Long id) {
        Generation generation = get(id);
        if (generation.isTerminal()) {
            return generation;
        }

        Prediction prediction;
        try {
            prediction = replicateClient.getPrediction(generation.getExternalId()); // il retry dei transitori e' di ReplicateClient
        } catch (RuntimeException e) {
            return handlePollFailure(generation, e);
        }

        if (prediction.succeeded()) {
            List<String> filenames = new ArrayList<>();
            try {
                List<String> outputUrls = prediction.outputUrls();
                if (outputUrls.isEmpty()) {
                    generation.setStatus(GenerationStatus.FAILED);
                    generation.setErrorMessage(messages.get("generation.error.noOutput"));
                } else {
                    for (int i = 0; i < outputUrls.size(); i++) {
                        filenames.add(imageStorageService.downloadAndStore(outputUrls.get(i)));
                    }
                    generation.setImageFilenames(filenames);
                    generation.setStatus(GenerationStatus.SUCCEEDED);
                    List<Long> loggedSeeds = seedsFromLogs(prediction.logs());
                    if (generation.getSeed() == null && !loggedSeeds.isEmpty()) {
                        generation.setSeed(loggedSeeds.get(0));
                    }
                    // Un seed per file solo se i log ne hanno esattamente uno per output: altrimenti l'abbinamento
                    // sarebbe incerto e i file ricadono sul seed del batch (Generation#seedOf).
                    if (filenames.size() > 1 && loggedSeeds.size() == filenames.size() && !loggedSeeds.contains(null)) {
                        Map<String, Long> perFile = new LinkedHashMap<>();
                        for (int i = 0; i < filenames.size(); i++) {
                            perFile.put(filenames.get(i), loggedSeeds.get(i));
                        }
                        generation.setImageSeeds(perFile);
                    }
                    ReplicatePricing.estimate(generation.getModel(), modelCatalog.formTypeOf(generation.getModel()).orElse(null), prediction.metrics())
                            .ifPresent(generation::setCostUsd);
                }
            } catch (RuntimeException e) {
                // Download/post-processing falliti: la prediction e' finita su Replicate ma il risultato
                // non e' recuperabile in modo affidabile (gli URL di output scadono). Stato terminale
                // FAILED (mai lasciarla PENDING/PROCESSING all'infinito), file parziali ripuliti.
                for (String written : filenames) {
                    try {
                        imageStorageService.delete(written);
                    } catch (RuntimeException cleanupFailure) {
                        e.addSuppressed(cleanupFailure); // la transizione a FAILED non deve dipendere dalla pulizia
                    }
                }
                generation.setImageFilenames(new ArrayList<>());
                systemEvents.record(CoreEventSource.STORAGE, "downloadOutput", e, AppEventSubjects.of(generation.getId(), generation.getConversationId()));
                generation.setStatus(GenerationStatus.FAILED);
                generation.setErrorMessage(messages.get("generation.error.downloadFailed", ISystemEvents.sanitize(e)));
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
        } else if (isPastTimeout(generation)) {
            failForTimeout(generation);
        } else {
            generation.setStatus(mapStatus(prediction.status()));
        }

        return saveAndLogIfTerminal(generation);
    }

    /** True se la generazione, ancora non terminale, ha superato il proprio timeout di business (image/video). */
    @Override
    public boolean isOverdue(Generation generation) {
        return !generation.isTerminal() && isPastTimeout(generation);
    }

    private boolean isPastTimeout(Generation generation) {
        return Duration.between(generation.getCreatedAt(), Instant.now()).compareTo(timeoutFor(generation.getKind())) > 0;
    }

    /** Timeout di business: FAILED + annullamento best-effort della prediction (altrimenti Replicate continua e fattura). */
    private void failForTimeout(Generation generation) {
        generation.setStatus(GenerationStatus.FAILED);
        generation.setErrorMessage(messages.get("generation.error.timeout", timeoutFor(generation.getKind()).toMinutes()));
        generation.setCompletedAt(Instant.now());
        cancelPredictionQuietly(generation.getExternalId(), generation.getId(), generation.getConversationId());
    }

    /**
     * Il poll verso Replicate e' fallito (dopo i ritentativi). Sempre registrato ({@link ISystemEvents}: la serie
     * evita righe/toast a ogni poll). Un errore PERMANENTE (token errato, 4xx, risposta illeggibile) fa fallire la
     * generazione subito; uno TRANSITORIO (rete, timeout, 5xx) la lascia in corso e riprova al prossimo poll — la
     * prediction su Replicate continua e il suo esito non va perso per un'interruzione di pochi secondi — ma il
     * timeout di business vale comunque, cosi' non esiste attesa infinita.
     */
    private Generation handlePollFailure(Generation generation, RuntimeException e) {
        systemEvents.record("getPrediction", e, AppEventSubjects.of(generation.getId(), generation.getConversationId()));
        boolean permanent = e instanceof ReplicateException replicateException && !replicateException.isTransient();
        if (permanent) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setErrorMessage(messages.get("generation.error.contactFailed", ISystemEvents.sanitize(e)));
            generation.setCompletedAt(Instant.now());
            // La riga diventa terminale e non verra' piu' interrogata: se la prediction gira ancora (risposta
            // illeggibile, non un 401/404) va fermata, altrimenti continua e costa.
            cancelPredictionQuietly(generation.getExternalId(), generation.getId(), generation.getConversationId());
            return saveAndLogIfTerminal(generation);
        }
        if (isPastTimeout(generation)) {
            failForTimeout(generation);
            return saveAndLogIfTerminal(generation);
        }
        return generation;
    }

    /**
     * Annullamento best-effort di una prediction che non ci serve piu' (timeout, cancellazione di una
     * generazione in corso, riga non salvabile). Non lancia mai: e' gia' un percorso di errore. Un 4xx
     * (prediction gia' terminale) e' atteso e solo loggato; il resto e' registrato.
     */
    private void cancelPredictionQuietly(String externalId, Long generationId, Long conversationId) {
        if (externalId == null || externalId.isBlank()) {
            return;
        }
        try {
            replicateClient.cancelPrediction(externalId);
        } catch (RuntimeException e) {
            if (e instanceof ReplicateException r && !r.isTransient()) {
                log.info("Annullamento della prediction {} non necessario/riuscito: {}", externalId, e.getMessage());
            } else {
                systemEvents.record("cancelPrediction", e, AppEventSubjects.of(generationId, conversationId));
            }
        }
    }

    /**
     * Interrompe una generazione in corso chiedendo a Replicate di
     * cancellare la prediction, poi fa avanzare lo stato (se Replicate ha
     * gia' risposto "canceled" la generazione diventa FAILED "annullata").
     * Se l'interruzione non riesce (prediction gia' terminale, errore di
     * rete...) la ReplicateException risale al chiamante e la generazione
     * resta com'e': chi la sta guardando attende la fine naturale.
     */
    @Override
    public Generation cancel(Long id) {
        Generation generation = get(id);
        if (generation.isTerminal()) {
            return generation;
        }
        replicateClient.cancelPrediction(generation.getExternalId());
        return refresh(id);
    }

    /** Associa una generazione avviata da /deep-chat alla sua conversazione (ripristino del placeholder al reload). */
    @Override
    public void attachToConversation(Long id, Long conversationId) {
        repository.findById(id).ifPresent(generation -> {
            generation.setConversationId(conversationId);
            repository.save(generation);
        });
    }

    @Override
    public void detachFromConversation(Long conversationId) {
        repository.clearConversation(conversationId);
    }

    /** Generazioni ancora in corso (non scadute) avviate dalla conversazione indicata. */
    @Override
    public List<Generation> inProgressForConversation(Long conversationId) {
        return repository.findByConversationIdAndStatusInAndCreatedAtAfterOrderByIdAsc(
                conversationId, List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING),
                Instant.now().minus(TIMEOUT));
    }

    /** Tutte le generazioni PENDING/PROCESSING (recupero all'avvio e sweep). */
    @Override
    public List<Generation> inProgress() {
        return repository.findByStatusIn(List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING));
    }

    /** Galleria contestuale: le generazioni RIUSCITE di una conversazione di /deep-chat, in ordine cronologico. */
    @Override
    public List<Generation> succeeded() {
        return repository.findByStatusIn(List.of(GenerationStatus.SUCCEEDED));
    }

    @Override
    public List<GalleryItem> succeededItemsForConversation(Long conversationId) {
        return repository.findByConversationIdAndStatusOrderByIdAsc(conversationId, GenerationStatus.SUCCEEDED).stream()
                .flatMap(generation -> GalleryItem.allOf(generation).stream())
                .toList();
    }

    /** Le generazioni esistenti fra gli id dati (gli id cancellati, o nulli, sono semplicemente assenti). */
    @Override
    public List<Generation> findAllById(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : repository.findAllById(ids);
    }

    /**
     * Id (i piu' recenti, al massimo {@code limit}) delle generazioni terminali di una conversazione completate prima di
     * {@code before}: la chat decide quali non hanno ancora il turno di esito.
     */
    @Override
    public BigDecimal totalCostSince(Instant since) {
        return repository.sumCostSince(since);
    }

    @Override
    public List<Long> terminalIdsWithConversation(Instant before, int limit) {
        return repository.findTerminalIdsWithConversation(
                List.of(GenerationStatus.SUCCEEDED, GenerationStatus.FAILED), before, limit);
    }

    /**
     * Salva e, solo se questa chiamata ha portato la generazione a uno
     * stato terminale (non ad ogni poll: refresh() ritorna subito se lo
     * e' gia', quindi qui si passa esattamente nel momento della
     * transizione), traccia l'esito del colloquio con Replicate a INFO
     * (successo) o WARN (fallimento, con l'errore restituito dall'API).
     */
    private Generation saveAndLogIfTerminal(Generation generation) {
        if (!repository.existsById(generation.getId())) {
            // La riga e' stata cancellata (GenerationController#deleteOne/delete/deleteImage/
            // deleteAll/deleteEverything) mentre questo refresh() era in volo su Replicate: da
            // /generations (vedi CLAUDE.md) anche generazioni non terminali sono ora cancellabili,
            // quindi questa corsa e' possibile (non lo era finche' solo generazioni SUCCEEDED,
            // sempre gia' terminali, erano esposte alla cancellazione). Le immagini appena
            // scaricate vanno comunque ripulite da disco, altrimenti resterebbero orfane - ma
            // niente da salvare, e SOPRATTUTTO niente ritornato al chiamante: un oggetto
            // "SUCCEEDED" con file gia' cancellati sarebbe un fantasma (GenerationController
            // #status lo mostrerebbe come se esistesse ancora, ChatGenerationWatcher
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
            log.info("Generazione completata: id={}, model={}, externalId={}, files={}, costUsd={}",
                    saved.getId(), saved.getModel(), saved.getExternalId(), saved.getImageFilenames(), saved.getCostUsd());
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
    @Override
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

    @Override
    public boolean exists(Long id) {
        return repository.existsById(id);
    }

    @Override
    public Optional<Generation> find(Long id) {
        return repository.findById(id);
    }

    @Override
    public Paged<GalleryItem> galleryPage(int pageIndex, int pageSize) {
        return repository.pageByStatus(GenerationStatus.SUCCEEDED, pageIndex, pageSize).map(GalleryItem::first);
    }

    @Override
    public Paged<GalleryItem> importedPage(int pageIndex, int pageSize) {
        return repository.pageSucceeded(null, GenerationOrigin.IMPORTED, pageIndex, pageSize).map(GalleryItem::first);
    }

    @Override
    public Paged<GalleryItem> imagePickerPage(boolean importedOnly, int pageIndex, int pageSize) {
        return repository.pageSucceeded(GenerationKind.IMAGE, importedOnly ? GenerationOrigin.IMPORTED : null, pageIndex, pageSize)
                .map(GalleryItem::first);
    }

    @Override
    public Paged<GalleryItem> favouritesPage(int pageIndex, int pageSize) {
        return repository.pageFavouriteItems(pageIndex, pageSize);
    }

    @Override
    public Paged<Generation> listPage(int pageIndex, int pageSize) {
        return repository.pageAll(pageIndex, pageSize);
    }

    @Override
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
        generations.forEach(generation -> {
            if (!generation.isTerminal()) {
                // Cancellare una generazione in corso non deve lasciare la prediction viva (e fatturata) su Replicate.
                cancelPredictionQuietly(generation.getExternalId(), generation.getId(), generation.getConversationId());
            }
            // Un file che non si lascia cancellare NON deve interrompere il batch a meta' (righe rimaste con i
            // file gia' spariti, evento mai pubblicato): la riga va comunque eliminata, il file orfano e' registrato.
            Stream.concat(generation.getImageFilenames().stream(),
                            Stream.of(generation.getSourceUploadFilename(), generation.getMaskUploadFilename()))
                    .forEach(file -> {
                        try {
                            imageStorageService.delete(file);
                        } catch (RuntimeException e) {
                            systemEvents.record(CoreEventSource.STORAGE, "deleteFile", e, AppEventSubjects.of(generation.getId(), generation.getConversationId()));
                        }
                    });
        });
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
    @Override
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
    @Override
    public void deleteAll(List<Long> ids) {
        deleteGenerations(repository.findAllById(ids));
    }

    /**
     * Azione nucleare (GenerationController#deleteAll, "Elimina tutto" in
     * /generations): elimina OGNI generazione esistente, non solo una
     * selezione. Riusa lo stesso deleteGenerations delle altre due varianti
     * sopra, quindi passa comunque per entita' caricate (mai bulk).
     */
    @Override
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
    @Override
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
        removeFiles(generation, List.of(filename));
        repository.save(generation);
        eventPublisher.publishEvent(new GenerationImageDeletedEvent(generationId));
        return false;
    }

    /**
     * Selezione per file (galleria contestuale di /deep-chat). A differenza di deleteImage, le voci sconosciute si ignorano (come
     * deleteAll). Le generazioni svuotate si raccolgono e si cancellano con UNA sola deleteGenerations (un solo
     * GenerationsDeletedEvent/refresh SSE); chi perde solo alcuni file ha un GenerationImageDeletedEvent.
     */
    @Override
    public void deleteImages(List<GenerationFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Map<Long, Set<String>> wantedByGeneration = new LinkedHashMap<>();
        for (GenerationFile file : files) {
            wantedByGeneration.computeIfAbsent(file.generationId(), id -> new LinkedHashSet<>()).add(file.filename());
        }
        List<Generation> emptied = new ArrayList<>();
        for (Generation generation : repository.findAllById(new ArrayList<>(wantedByGeneration.keySet()))) {
            Set<String> wanted = wantedByGeneration.get(generation.getId());
            // Solo file GIA' registrati per QUESTA generazione (stessa guardia anti path-traversal di deleteImage).
            List<String> toRemove = generation.getImageFilenames().stream().filter(wanted::contains).toList();
            if (toRemove.isEmpty()) {
                continue;
            }
            if (toRemove.size() == generation.getImageFilenames().size()) {
                emptied.add(generation);
                continue;
            }
            removeFiles(generation, toRemove);
            repository.save(generation);
            eventPublisher.publishEvent(new GenerationImageDeletedEvent(generation.getId()));
        }
        if (!emptied.isEmpty()) {
            deleteGenerations(emptied);
        }
    }

    /** Toglie i file dallo storage e dalla generazione (elenco, star, seed per file); il chiamante salva e pubblica l'evento. */
    private void removeFiles(Generation generation, Collection<String> filenames) {
        filenames.forEach(imageStorageService::delete);
        List<String> remaining = new ArrayList<>(generation.getImageFilenames());
        remaining.removeAll(filenames);
        generation.setImageFilenames(remaining);
        generation.getFavouriteFilenames().removeAll(filenames);
        generation.getImageSeeds().keySet().removeAll(filenames);
    }

    /**
     * Inverte la star di UN file di una generazione (V16). Stessa guardia
     * anti path-traversal di deleteImage: il filename deve essere uno di
     * quelli gia' registrati per QUESTA generazione.
     *
     * @return il nuovo stato: true se ora e' preferito
     */
    @Override
    public boolean toggleFavourite(Long generationId, String filename) {
        Generation generation = get(generationId);
        if (!generation.getImageFilenames().contains(filename)) {
            throw new ReplicateException(messages.get("gallery.error.imageNotFound"));
        }
        Set<String> favourites = new LinkedHashSet<>(generation.getFavouriteFilenames());
        boolean nowFavourite = favourites.add(filename);
        if (!nowFavourite) {
            favourites.remove(filename);
        }
        generation.setFavouriteFilenames(favourites);
        repository.save(generation);
        eventPublisher.publishEvent(new GenerationFavouriteToggledEvent(generationId));
        return nowFavourite;
    }

    private String toJson(Map<String, Object> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
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
