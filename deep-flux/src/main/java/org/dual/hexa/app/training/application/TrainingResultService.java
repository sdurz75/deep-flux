package org.dual.hexa.app.training.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.dual.hexa.app.generation.domain.ApiTokenProvider;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.app.generation.port.in.IModelCatalog;
import org.dual.hexa.app.shared.domain.AppEventSource;
import org.dual.hexa.app.shared.domain.AppEventSubjects;
import org.dual.hexa.app.training.domain.HfStatus;
import org.dual.hexa.app.training.domain.HuggingFaceException;
import org.dual.hexa.app.training.domain.ModelStatus;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.event.TrainingChangedEvent;
import org.dual.hexa.app.training.port.in.ITrainingDatasets;
import org.dual.hexa.app.training.port.in.ITrainingResults;
import org.dual.hexa.app.training.port.out.IHuggingFaceRepos;
import org.dual.hexa.app.training.port.out.ITrainingStore;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.tokens.domain.TokenException;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Il risultato di un training riuscito, in tre passi indipendenti e idempotenti (ognuno ricorda a che punto e' sulla riga e salva appena fatto, cosi' un guasto
 * dopo non lo perde):
 * <ol>
 *   <li><b>preset</b> in /loras: nome, sorgente {@code owner/nome} del modello Replicate di destinazione, intensita' predefinita, trigger word e una nota. Un nome
 *       gia' preso riceve la data come suffisso. Se un tentativo precedente aveva creato il preset ma non aveva fatto in tempo a salvarne l'id, si riprende quello (stessa
 *       sorgente) invece di crearne un secondo;</li>
 *   <li><b>modello utilizzabile</b>: il preset lo censisce gia' da se' (regola "preset = modello"), ma un rifiuto li' e' silenzioso, quindi qui si controlla che il modello
 *       sia nel catalogo e, se no, si riprova a censirlo. Subito dopo il successo la versione puo' non essere ancora visibile su Replicate: un rifiuto entro il
 *       periodo di tolleranza si ritenta, dopo e' definitivo e ne nasce un avviso;</li>
 *   <li><b>copia su HuggingFace</b>: il repo lo ha creato l'app PRIMA del training, quindi la sua esistenza non prova nulla; si controlla che ci siano file di pesi
 *       ({@code .safetensors}). Il token si risolve di nuovo (si usa quello salvato nello snapshot): se non c'e' piu' la verifica non e' possibile, e non e' un errore.</li>
 * </ol>
 * Nessun passo fa mai fallire il training: i pesi esistono comunque. Condivide con {@code TrainingService} i lock per training.
 */
@Service
public class TrainingResultService implements ITrainingResults {

    private static final String WEIGHTS_SUFFIX = ".safetensors";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withLocale(Locale.ITALY);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withLocale(Locale.ITALY);

    private final ITrainingStore store;
    private final ITrainingDatasets datasets;
    private final ILoraPresets presets;
    private final IModelCatalog catalog;
    private final IHuggingFaceRepos huggingFace;
    private final IApiTokens tokens;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration grace;
    private final Duration retryWindow;

    @Autowired
    public TrainingResultService(ITrainingStore store, ITrainingDatasets datasets, ILoraPresets presets, IModelCatalog catalog, IHuggingFaceRepos huggingFace,
                                 IApiTokens tokens, ISystemEvents systemEvents, Messages messages, ApplicationEventPublisher eventPublisher,
                                 @Value("${app.training.result-grace:10m}") Duration grace,
                                 @Value("${app.training.result-retry-window:6h}") Duration retryWindow) {
        this(store, datasets, presets, catalog, huggingFace, tokens, systemEvents, messages, eventPublisher, Clock.systemDefaultZone(), grace, retryWindow);
    }

    TrainingResultService(ITrainingStore store, ITrainingDatasets datasets, ILoraPresets presets, IModelCatalog catalog, IHuggingFaceRepos huggingFace,
                          IApiTokens tokens, ISystemEvents systemEvents, Messages messages, ApplicationEventPublisher eventPublisher, Clock clock,
                          Duration grace, Duration retryWindow) {
        this.store = store;
        this.datasets = datasets;
        this.presets = presets;
        this.catalog = catalog;
        this.huggingFace = huggingFace;
        this.tokens = tokens;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.grace = grace;
        this.retryWindow = retryWindow;
    }

    @Override
    public void complete(Long trainingId) {
        if (trainingId == null) {
            return;
        }
        synchronized (TrainingLocks.of(trainingId)) {
            Training training = store.findById(trainingId).orElse(null);
            if (training == null || !training.isResultIncomplete()) {
                return; // eliminato nel frattempo, non riuscito, o gia' completo
            }
            boolean changed = step(training, "completeTrainingPreset", this::ensurePreset);
            changed |= step(training, "completeTrainingModel", this::ensureModel);
            changed |= step(training, "completeTrainingHuggingFace", this::verifyHuggingFace);
            if (changed) {
                eventPublisher.publishEvent(new TrainingChangedEvent(trainingId));
            }
        }
    }

    @Override
    public int recoverPending() {
        List<Training> pending = store.findResultsToComplete(clock.instant().minus(retryWindow));
        for (Training training : pending) {
            try {
                complete(training.getId());
            } catch (RuntimeException e) {
                systemEvents.record("recoverTrainingResult", e, AppEventSubjects.ofTraining(training.getId()));
            }
        }
        return pending.size();
    }

    /** Un passo: un guasto inatteso e' registrato e il passo resta da rifare, senza impedire quelli dopo. Salva appena il passo ha cambiato qualcosa. */
    private boolean step(Training training, String operation, Step step) {
        try {
            boolean changed = step.run(training);
            if (changed) {
                store.save(training);
            }
            return changed;
        } catch (RuntimeException e) {
            systemEvents.record(operation, e, AppEventSubjects.ofTraining(training.getId()));
            return false;
        }
    }

    @FunctionalInterface
    private interface Step {
        boolean run(Training training);
    }

    // --- 1. preset ----------------------------------------------------------------------------------------------

    private boolean ensurePreset(Training training) {
        if (training.getPresetId() != null) {
            return false;
        }
        List<ILoraPresets.LoraView> existing = presets.list();
        String source = training.destinationModel();
        Optional<ILoraPresets.LoraView> adopted = existing.stream().filter(p -> source.equals(p.source())).findFirst();
        if (adopted.isPresent()) {
            training.setPresetId(adopted.get().id());
            return true;
        }
        Set<String> taken = existing.stream().map(p -> p.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String name = uniqueName(training, taken);
        String note = messages.get("training.result.presetNote", training.getId(), DAY.format(training.getCreatedAt().atZone(ZoneId.systemDefault())));
        ILoraPresets.LoraView preset = presets.create(name, source, ILoraPresets.DEFAULT_SCALE, training.getTriggerWord(), note);
        training.setPresetId(preset.id());
        return true;
    }

    /** Il nome del dataset; se e' preso, con la data; poi con data e ora; poi con l'id del training: sempre entro {@code ILoraPresets.MAX_NAME}. */
    private String uniqueName(Training training, Set<String> taken) {
        ZoneId zone = ZoneId.systemDefault();
        String base = training.getName();
        List<String> suffixes = List.of("", " (" + DAY.format(training.getCreatedAt().atZone(zone)) + ")",
                " (" + MINUTE.format(training.getCreatedAt().atZone(zone)) + ")", " #" + training.getId());
        for (String suffix : suffixes) {
            String candidate = fit(base, suffix);
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return fit(base, " #" + training.getId()); // l'id e' unico: l'ultima scelta non collide con un altro training, e un nome uguale lo rifiuta il servizio dei preset
    }

    private static String fit(String base, String suffix) {
        int room = ILoraPresets.MAX_NAME - suffix.length();
        String trimmed = base.length() > room ? base.substring(0, room).strip() : base;
        return trimmed + suffix;
    }

    // --- 2. modello utilizzabile --------------------------------------------------------------------------------

    private boolean ensureModel(Training training) {
        if (training.getModelStatus() != ModelStatus.PENDING) {
            return false;
        }
        String model = training.destinationModel();
        if (catalog.contains(model)) {
            training.setModelStatus(ModelStatus.REGISTERED);
            return true;
        }
        try {
            Optional<?> registered = catalog.registerLoraFinetune(model, training.getName());
            if (registered.isPresent() || catalog.contains(model)) {
                training.setModelStatus(ModelStatus.REGISTERED);
                return true;
            }
            // Gia' censito ma non attivo: ritentare non lo riattiva.
            return reject(training, messages.get("training.result.modelInactive"));
        } catch (RemoteServiceException e) {
            if (e.kind() != RemoteServiceException.Kind.REJECTED) {
                // Guasto del servizio (rete, 5xx, token): resta da rifare; l'evento lo registra, lo sweep riprova.
                systemEvents.record("registerTrainedModel", e, AppEventSubjects.ofTraining(training.getId()));
                return false;
            }
            // Subito dopo il successo la versione puo' non essere ancora visibile: si ritenta finche' dura la tolleranza, poi e' definitivo.
            return withinGrace(training) ? false : reject(training, e.getMessage());
        }
    }

    private boolean reject(Training training, String reason) {
        training.setModelStatus(ModelStatus.REJECTED);
        systemEvents.warn(AppEventSource.TRAINING, "trainedModelUnusable", AppEventSubjects.ofTraining(training.getId()),
                messages.get("training.result.modelUnusable", training.getId(), training.destinationModel(), reason));
        return true;
    }

    // --- 3. copia su HuggingFace --------------------------------------------------------------------------------

    private boolean verifyHuggingFace(Training training) {
        if (training.getHfStatus() != HfStatus.PENDING) {
            return false;
        }
        Long tokenId = datasets.find(training.getSnapshotDatasetId()).map(TrainingDataset::getHfTokenId).orElse(null);
        String token = null;
        if (tokenId != null) {
            try {
                token = tokens.resolve(tokenId, ApiTokenProvider.HUGGINGFACE.name());
            } catch (TokenException e) {
                token = null; // scaduto o cancellato: succede, la verifica non e' possibile
            }
        }
        if (token == null) {
            training.setHfStatus(HfStatus.UNVERIFIED);
            return true;
        }
        try {
            boolean weights = huggingFace.repoFiles(token, training.getHfRepoId())
                    .map(files -> files.stream().anyMatch(f -> f.toLowerCase(Locale.ROOT).endsWith(WEIGHTS_SUFFIX))).orElse(false);
            if (weights) {
                training.setHfStatus(HfStatus.VERIFIED);
                return true;
            }
            if (withinGrace(training)) {
                return false; // l'elenco dei file puo' non essere ancora aggiornato
            }
            training.setHfStatus(HfStatus.NOT_FOUND);
            systemEvents.warn(AppEventSource.TRAINING, "hfWeightsMissing", AppEventSubjects.ofTraining(training.getId()),
                    messages.get("training.result.hfMissing", training.getId(), training.getHfRepoId()));
            return true;
        } catch (HuggingFaceException e) {
            if (e.isTransient()) {
                systemEvents.record("verifyHuggingFaceRepo", e, AppEventSubjects.ofTraining(training.getId()));
                return false; // si riprova al prossimo giro
            }
            if (e.isReportable()) {
                systemEvents.record("verifyHuggingFaceRepo", e, AppEventSubjects.ofTraining(training.getId()));
            }
            training.setHfStatus(HfStatus.UNVERIFIED); // token non piu' valido o repo non leggibile: non si insiste
            return true;
        }
    }

    private boolean withinGrace(Training training) {
        Instant completed = training.getCompletedAt() != null ? training.getCompletedAt() : training.getCreatedAt();
        return Duration.between(completed, clock.instant()).compareTo(grace) < 0;
    }
}
