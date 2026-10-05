package org.dual.replicate.app.training.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.ApiTokenProvider;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.app.training.domain.ArchiveItem;
import org.dual.replicate.app.training.domain.DatasetArchive;
import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.LaunchCheck;
import org.dual.replicate.app.training.domain.TrainerJob;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.domain.event.TrainingChangedEvent;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.app.training.port.in.ITrainings;
import org.dual.replicate.app.training.port.out.IDatasetArchiver;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.app.training.port.out.ITrainerGateway;
import org.dual.replicate.app.training.port.out.ITrainingStore;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.Paged;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Use case dei training. Come per le generazioni, NON e' {@code @Transactional}: tra una chiamata remota e l'altra non si tiene aperta una transazione, e gli
 * stati intermedi sono righe salvate. Il lancio e' l'unica operazione che SPENDE, quindi e' costruita attorno a due regole:
 * <ul>
 *   <li><b>tutto cio' che puo' fallire a basso costo viene PRIMA</b> (controlli locali, token HuggingFace, snapshot, zip, caricamento, modello di destinazione,
 *       repo), e qualunque fallimento prima dell'avvio ripulisce lo snapshot; {@code createTraining} non si ritenta mai;</li>
 *   <li><b>nessun training senza riga</b>: se il salvataggio fallisce dopo l'avvio remoto, il training si annulla.</li>
 * </ul>
 * Il token HuggingFace in chiaro esiste solo dentro {@link #start}: va a Replicate come {@code hf_token} (un segreto del trainer) e non entra in nessuna riga, log o
 * evento. Un solo aggiornamento alla volta per training (poller della pagina, sweep di recupero e utente si incrociano).
 */
@Service
public class TrainingService implements ITrainings {

    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    /** Lock a strisce per training (id % N): nessuna mappa che cresce, collisioni innocue (solo serializzazione in piu'). */
    private static final Object[] LOCKS = java.util.stream.Stream.generate(Object::new).limit(64).toArray();

    /** Lock a strisce per BOZZA (id % N), distinti da quelli per training: serializzano i lanci della stessa bozza (vedi {@link #start}). */
    private static final Object[] START_LOCKS = java.util.stream.Stream.generate(Object::new).limit(64).toArray();

    /** Data e ora (secondi) nel nome del modello di destinazione: ogni training ha il PROPRIO modello Replicate. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Lunghezza massima della parte "leggibile" del nome: i nomi dei modelli Replicate hanno un tetto e la data ne occupa 15. */
    private static final int SLUG_MAX = 40;

    private final ITrainingStore store;
    private final ITrainingDatasets datasets;
    private final ITrainerGateway trainer;
    private final IHuggingFaceRepos huggingFace;
    private final IDatasetArchiver archiver;
    private final IApiTokens tokens;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final int minImages;
    private final int warnImages;
    private final Duration timeout;

    @Autowired
    public TrainingService(ITrainingStore store, ITrainingDatasets datasets, ITrainerGateway trainer, IHuggingFaceRepos huggingFace,
                           IDatasetArchiver archiver, IApiTokens tokens, ISystemEvents systemEvents, Messages messages,
                           ApplicationEventPublisher eventPublisher, @Value("${app.training.min-images:4}") int minImages,
                           @Value("${app.training.warn-images:10}") int warnImages, @Value("${app.training.timeout:2h}") Duration timeout) {
        this(store, datasets, trainer, huggingFace, archiver, tokens, systemEvents, messages, eventPublisher, Clock.systemDefaultZone(), minImages,
                warnImages, timeout);
    }

    TrainingService(ITrainingStore store, ITrainingDatasets datasets, ITrainerGateway trainer, IHuggingFaceRepos huggingFace, IDatasetArchiver archiver,
                    IApiTokens tokens, ISystemEvents systemEvents, Messages messages, ApplicationEventPublisher eventPublisher, Clock clock,
                    int minImages, int warnImages, Duration timeout) {
        this.store = store;
        this.datasets = datasets;
        this.trainer = trainer;
        this.huggingFace = huggingFace;
        this.archiver = archiver;
        this.tokens = tokens;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.minImages = minImages;
        this.warnImages = warnImages;
        this.timeout = timeout;
    }

    // --- lettura ------------------------------------------------------------------------------------------------

    @Override
    public Paged<Training> page(int pageIndex, int pageSize) {
        return store.findPage(pageIndex, pageSize);
    }

    @Override
    public Optional<Training> find(Long id) {
        return id == null ? Optional.empty() : store.findById(id);
    }

    @Override
    public Training get(Long id) {
        return find(id).orElseThrow(() -> new TrainingException(messages.get("training.error.trainingNotFound")));
    }

    @Override
    public Optional<Training> findBySnapshot(Long snapshotDatasetId) {
        return snapshotDatasetId == null ? Optional.empty() : store.findBySnapshotDatasetId(snapshotDatasetId);
    }

    @Override
    public List<Training> inProgress() {
        return store.findByStatusIn(List.of(TrainingStatus.PENDING, TrainingStatus.PROCESSING));
    }

    // --- controllo di lancio ------------------------------------------------------------------------------------

    @Override
    public LaunchCheck check(Long datasetId) {
        LaunchCheck check = check(datasets.get(datasetId));
        if (!runningTrainingsOf(datasetId).isEmpty()) {
            List<String> blockers = new ArrayList<>(check.blockers());
            blockers.add(0, messages.get("training.check.alreadyRunning"));
            return new LaunchCheck(blockers, check.warnings());
        }
        return check;
    }

    /** I training non terminali lanciati da questa bozza: un secondo lancio, mentre uno e' in corso, non e' mai voluto (e costa). */
    private List<Training> runningTrainingsOf(Long draftId) {
        return inProgress().stream().filter(t -> draftId.equals(t.getSourceDatasetId())).toList();
    }

    private LaunchCheck check(TrainingDataset dataset) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<TrainingImage> images = dataset.getImages();

        if (images.size() < minImages) {
            blockers.add(messages.get("training.check.tooFewImages", minImages, images.size()));
        }
        long pending = images.stream().filter(TrainingImage::isCaptionPending).count();
        if (pending > 0) {
            blockers.add(messages.get("training.check.captionsPending", pending));
        }
        long missing = images.stream().filter(i -> !i.isCaptionPending() && isBlank(i.getCaption())).count();
        if (missing > 0) {
            blockers.add(messages.get("training.check.captionsMissing", missing));
        }
        if (dataset.getTrainingSteps() < datasets.minSteps() || dataset.getTrainingSteps() > datasets.maxSteps()) {
            blockers.add(messages.get("training.check.stepsOutOfRange", datasets.minSteps(), datasets.maxSteps()));
        }
        if (dataset.isHfPublish()) {
            checkHuggingFaceToken(dataset, blockers);
        }

        if (images.size() >= minImages && images.size() < warnImages) {
            warnings.add(messages.get("training.check.fewImages", warnImages, images.size()));
        }
        if (dataset.isHfPublish() && dataset.getHfRepoName() != null) {
            warnings.add(messages.get("training.check.hfRepoShared", slug(dataset.getHfRepoName())));
        }
        long withoutTrigger = images.stream().filter(i -> i.isCaptionMissingTrigger(dataset.getTriggerWord())).count();
        if (withoutTrigger > 0) {
            warnings.add(messages.get("training.check.triggerMissing", withoutTrigger, dataset.getTriggerWord()));
        }
        return new LaunchCheck(blockers, warnings);
    }

    private void checkHuggingFaceToken(TrainingDataset dataset, List<String> blockers) {
        if (dataset.getHfTokenId() == null) {
            blockers.add(messages.get("training.check.hfTokenRequired"));
            return;
        }
        try {
            IApiTokens.TokenView token = tokens.get(dataset.getHfTokenId());
            if (token.status() == IApiTokens.Status.EXPIRED) {
                blockers.add(messages.get("training.check.hfTokenExpired", token.name()));
            }
        } catch (TokenException e) {
            blockers.add(messages.get("training.check.hfTokenMissing"));
        }
    }

    // --- lancio -------------------------------------------------------------------------------------------------

    @Override
    public Training start(Long datasetId) {
        // Un solo lancio alla volta per bozza: il blocco dell'interfaccia protegge solo il client, e due POST (un doppio invio, un submit nativo dopo un reload)
        // avvierebbero due training a pagamento. Il secondo aspetta qui e poi trova il primo gia' salvato fra quelli in corso.
        synchronized (START_LOCKS[(int) (Math.abs(datasetId) % START_LOCKS.length)]) {
            return doStart(datasetId);
        }
    }

    private Training doStart(Long datasetId) {
        TrainingDataset dataset = datasets.get(datasetId);
        if (dataset.isFrozen()) {
            throw new TrainingException(messages.get("training.error.frozen"));
        }
        if (!runningTrainingsOf(datasetId).isEmpty()) {
            throw new TrainingException(messages.get("training.check.alreadyRunning"));
        }
        LaunchCheck check = check(dataset);
        if (!check.isLaunchable()) {
            throw new TrainingException(check.blockers().get(0));
        }
        Instant now = clock.instant();

        // 1. Cio' che costa niente e si puo' sapere prima: il token HuggingFace e a chi appartiene (sola lettura).
        String hfToken = null;
        HfAccount hfAccount = null;
        if (dataset.isHfPublish()) {
            hfToken = tokens.resolve(dataset.getHfTokenId(), ApiTokenProvider.HUGGINGFACE.name());
            hfAccount = huggingFace.whoami(hfToken);
            if (hfAccount.isReadOnly()) {
                throw new TrainingException(messages.get("training.error.hfReadOnly"));
            }
        }

        // 2. Lo snapshot: da qui in poi c'e' qualcosa da ripulire se il lancio non arriva in fondo.
        TrainingDataset snapshot = datasets.snapshot(datasetId);
        try {
            // Quello che parte e' lo snapshot, non la bozza (potrebbe essere cambiata fra il controllo e la copia): si ricontrolla su di lui.
            LaunchCheck snapshotCheck = check(snapshot);
            if (!snapshotCheck.isLaunchable()) {
                throw new TrainingException(snapshotCheck.blockers().get(0));
            }
            return launch(snapshot, hfToken, hfAccount, now);
        } catch (RuntimeException e) {
            deleteSnapshotQuietly(snapshot.getId(), e);
            throw e;
        }
    }

    /**
     * Dallo snapshot all'avvio remoto e alla riga. L'ORDINE conta: prima le letture e i passi che non lasciano nulla sull'account (versione del trainer, zip e
     * caricamento del file), poi quello che fallisce piu' facilmente fra le scritture (il repo HuggingFace: un token fine-grained senza permesso di creazione),
     * poi il modello di destinazione su Replicate, e solo alla fine {@code createTraining}. Cosi' un fallimento intermedio non lascia un modello Replicate
     * orfano per un repo che non si riusciva a creare.
     */
    private Training launch(TrainingDataset snapshot, String hfToken, HfAccount hfAccount, Instant now) {
        String trainerVersion = trainer.trainerVersion();
        String fileUrl;
        try (DatasetArchive zip = archiver.build(archiveItems(snapshot))) {
            fileUrl = trainer.uploadFile("dataset.zip", zip);
        }

        String destinationName = destinationName(snapshot, now);
        String hfRepoId = null;
        if (hfAccount != null) {
            String repoName = hfRepoName(snapshot, destinationName);
            huggingFace.createModelRepo(hfToken, repoName, snapshot.isHfPrivate());
            hfRepoId = hfAccount.username() + "/" + repoName;
        }

        String destination = trainer.ensureDestination(destinationName);
        TrainerJob job = trainer.createTraining(trainerVersion, destination, trainerInput(snapshot, fileUrl, hfRepoId, hfToken));

        String[] ownerAndName = destination.split("/", 2);
        Training saved;
        try {
            saved = store.save(new Training(snapshot, job.externalId(), job.status(), trainerVersion, ownerAndName[0], ownerAndName[1], hfRepoId, now));
        } catch (RuntimeException e) {
            // Il training e' partito (e costa) ma non c'e' una riga che lo tenga d'occhio: si ferma.
            cancelQuietly(job.externalId(), null);
            throw e;
        }
        changed(saved);
        return saved;
    }

    private static Map<String, Object> trainerInput(TrainingDataset snapshot, String fileUrl, String hfRepoId, String hfToken) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input_images", fileUrl);
        input.put("trigger_word", snapshot.getTriggerWord());
        input.put("lora_type", snapshot.getLoraType().trainerValue());
        input.put("training_steps", snapshot.getTrainingSteps());
        if (snapshot.getSeed() != null) {
            input.put("seed", snapshot.getSeed());
        }
        if (hfRepoId != null) {
            input.put("hf_repo_id", hfRepoId);
            input.put("hf_token", hfToken);
        }
        return input;
    }

    /** Le immagini nell'ordine del dataset, con il nome che avranno nello zip: {@code img_001.<estensione>} e il suo {@code img_001.txt} accanto. */
    private static List<ArchiveItem> archiveItems(TrainingDataset snapshot) {
        List<ArchiveItem> items = new ArrayList<>();
        int number = 1;
        for (TrainingImage image : snapshot.getImages()) {
            String base = "img_%03d".formatted(number++);
            items.add(new ArchiveItem(base + "." + extensionOf(image.getFilename()), image.getFilename(), base + ".txt", image.getCaption().strip()));
        }
        return items;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "jpg";
    }

    /** {@code <nome-leggibile>-<aaaammgg-hhmmss>}: unico per training (un modello Replicate gia' addestrato non e' un buon punto di partenza per un altro). */
    private String destinationName(TrainingDataset snapshot, Instant now) {
        String base = snapshot.getModelName() != null ? snapshot.getModelName() : snapshot.getName();
        return slug(base) + "-" + STAMP.format(now);
    }

    /**
     * Il nome del repo HuggingFace: di DEFAULT quello del modello di destinazione, che ha data e ora e quindi e' unico per training (un repo gia' esistente e'
     * "va bene": se due training lo condividessero, il secondo scriverebbe i pesi nel repo del primo e lo storico del primo continuerebbe a dire che ci sono).
     * Un nome scelto a mano si rispetta, ma il pannello avvisa (vedi {@code check}).
     */
    private static String hfRepoName(TrainingDataset snapshot, String destinationName) {
        return snapshot.getHfRepoName() != null ? slug(snapshot.getHfRepoName()) : destinationName;
    }

    /** Minuscolo, tutto cio' che non e' lettera o cifra diventa un trattino, trattini ripetuti o ai bordi tolti; mai vuoto. */
    static String slug(String text) {
        String clean = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (clean.length() > SLUG_MAX) {
            clean = clean.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        return clean.isEmpty() ? "lora" : clean;
    }

    // --- avanzamento --------------------------------------------------------------------------------------------

    @Override
    public Training refresh(Long id) {
        synchronized (lockOf(id)) {
            return doRefresh(id);
        }
    }

    private Training doRefresh(Long id) {
        Training training = get(id);
        if (training.isTerminal() || training.getExternalId() == null) {
            return training;
        }
        TrainerJob job;
        try {
            job = trainer.getTraining(training.getExternalId()); // il retry dei transitori e' del gateway
        } catch (RuntimeException e) {
            return handlePollFailure(training, e);
        }

        Instant now = clock.instant();
        TrainingStatus before = training.getStatus();
        if (job.status().isTerminal()) {
            applyTerminal(training, job, now);
        } else if (isPastTimeout(training)) {
            failForTimeout(training, now);
        } else {
            training.advance(job.status(), job.logs() != null ? job.logs() : training.getLogs());
        }
        return saveAndNotify(training, before);
    }

    /** L'esito terminale riferito da Replicate (riuscito, fallito, annullato) sulla riga: lo stesso per un poll e per la risposta di un annullamento. */
    private void applyTerminal(Training training, TrainerJob job, Instant now) {
        switch (job.status()) {
            case SUCCEEDED -> training.succeed(job.logs() != null ? job.logs() : training.getLogs(), job.predictTimeSeconds(), now);
            case FAILED -> {
                String message = isBlank(job.error()) ? messages.get("training.error.failedGeneric") : job.error();
                training.fail(TrainingStatus.FAILED, message, job.logs(), job.predictTimeSeconds(), now);
                warnFailed(training);
            }
            case CANCELED -> training.fail(TrainingStatus.CANCELED, messages.get("training.error.canceled"), job.logs(), job.predictTimeSeconds(), now);
            default -> throw new IllegalStateException("Stato non terminale: " + job.status());
        }
    }

    @Override
    public boolean isOverdue(Training training) {
        return !training.isTerminal() && isPastTimeout(training);
    }

    private boolean isPastTimeout(Training training) {
        return Duration.between(training.getCreatedAt(), clock.instant()).compareTo(timeout) > 0;
    }

    /** Timeout di business: FAILED + annullamento best-effort (altrimenti Replicate continua e fattura). */
    private void failForTimeout(Training training, Instant now) {
        training.fail(TrainingStatus.FAILED, messages.get("training.error.timeout", timeout.toMinutes()), null, null, now);
        cancelQuietly(training.getExternalId(), training.getId());
        warnFailed(training);
    }

    /**
     * Il poll e' fallito (dopo i ritentativi). Sempre registrato (la serie evita righe e toast a ogni poll). Un errore PERMANENTE fa fallire il training subito e
     * lo ferma; uno TRANSITORIO (o di configurazione) lo lascia in corso e riprova al prossimo giro (il training su Replicate continua: il suo esito non va perso per un'interruzione di
     * pochi secondi), ma il timeout di business vale comunque, cosi' non esiste attesa infinita.
     */
    private Training handlePollFailure(Training training, RuntimeException e) {
        systemEvents.record("getTraining", e, AppEventSubjects.ofTraining(training.getId()));
        TrainingStatus before = training.getStatus();
        // Solo un 4xx/risposta illeggibile (PERMANENT) chiude il training: un token di Replicate mancante (CONFIGURATION) e' un problema di chi lo gestisce, non del
        // training, che su Replicate gira comunque. Resta in corso, l'evento e' registrato, e il timeout di business vale lo stesso.
        boolean permanent = e instanceof RemoteServiceException remote && remote.kind() == RemoteServiceException.Kind.PERMANENT;
        Instant now = clock.instant();
        if (permanent) {
            training.fail(TrainingStatus.FAILED, messages.get("training.error.contactFailed", ISystemEvents.sanitize(e)), null, null, now);
            // La riga diventa terminale e non si interroga piu': se il training gira ancora va fermato, altrimenti continua e costa.
            cancelQuietly(training.getExternalId(), training.getId());
            warnFailed(training);
            return saveAndNotify(training, before);
        }
        if (isPastTimeout(training)) {
            failForTimeout(training, now);
            return saveAndNotify(training, before);
        }
        return training;
    }

    // --- annullamento ed eliminazione ---------------------------------------------------------------------------

    @Override
    public Training cancel(Long id) {
        synchronized (lockOf(id)) {
            Training training = get(id);
            if (training.isTerminal()) {
                throw new TrainingException(messages.get("training.error.alreadyFinished"));
            }
            TrainingStatus before = training.getStatus();
            TrainerJob job;
            try {
                job = trainer.cancelTraining(training.getExternalId());
            } catch (RuntimeException e) {
                if (e instanceof RemoteServiceException remote && !remote.isTransient()) {
                    // Gia' terminale su Replicate (o non piu' raggiungibile con questo token): non c'e' nulla da fermare, si legge lo stato vero.
                    log.info("Annullamento del training {} non riuscito, se ne rilegge lo stato: {}", id, e.getMessage());
                    return doRefresh(id);
                }
                throw e; // transitorio: non e' stato annullato e nulla cambia, l'utente puo' riprovare
            }
            // Replicate risponde con lo stato vero: se il training era appena finito (riuscito o fallito) e' QUELLO l'esito, non "annullato" (altrimenti un
            // training riuscito diventerebbe annullato e il suo risultato non verrebbe mai usato). Annullato solo se non e' ancora terminale.
            Instant now = clock.instant();
            if (job.status().isTerminal()) {
                applyTerminal(training, job, now);
            } else {
                training.fail(TrainingStatus.CANCELED, messages.get("training.error.canceled"), job.logs(), job.predictTimeSeconds(), now);
            }
            return saveAndNotify(training, before);
        }
    }

    @Override
    public void delete(Long id) {
        synchronized (lockOf(id)) {
            Training training = get(id);
            if (!training.isTerminal()) {
                cancelQuietly(training.getExternalId(), training.getId());
            }
            store.delete(training);
            try {
                datasets.deleteSnapshot(training.getSnapshotDatasetId());
            } catch (TrainingException e) {
                if (e.isReportable()) {
                    systemEvents.record("deleteTrainingSnapshot", e, AppEventSubjects.ofTraining(id));
                } // altrimenti lo snapshot non c'e' piu': niente da eliminare
            }
            changed(training);
        }
    }

    // --- interno ------------------------------------------------------------------------------------------------

    private Training saveAndNotify(Training training, TrainingStatus before) {
        Training saved = store.save(training);
        if (saved.getStatus() != before) {
            changed(saved);
        }
        return saved;
    }

    private void changed(Training training) {
        eventPublisher.publishEvent(new TrainingChangedEvent(training.getId()));
    }

    /** Un training FALLITO e' una cosa che l'utente vuole sapere anche con la pagina chiusa: avviso (campanella e toast), una volta per training. */
    private void warnFailed(Training training) {
        systemEvents.warn(AppEventSource.TRAINING, "trainingFailed", AppEventSubjects.ofTraining(training.getId()),
                messages.get("training.event.failed", training.getName(), training.getErrorMessage()));
    }

    /** Annullamento best-effort di un training che non ci serve piu' (timeout, errore permanente, riga non salvabile). Non lancia mai: e' gia' un percorso di errore. */
    private void cancelQuietly(String externalId, Long trainingId) {
        if (externalId == null || externalId.isBlank()) {
            return;
        }
        try {
            trainer.cancelTraining(externalId);
        } catch (RuntimeException e) {
            if (e instanceof RemoteServiceException remote && !remote.isTransient()) {
                log.info("Annullamento del training {} non necessario/riuscito: {}", externalId, e.getMessage());
            } else {
                systemEvents.record("cancelTraining", e, AppEventSubjects.ofTraining(trainingId));
            }
        }
    }

    private void deleteSnapshotQuietly(Long snapshotId, RuntimeException cause) {
        try {
            datasets.deleteSnapshot(snapshotId);
        } catch (RuntimeException cleanupFailure) {
            cause.addSuppressed(cleanupFailure); // il guasto vero e' la causa: la pulizia fallita non deve nasconderlo
        }
    }

    private static Object lockOf(Long id) {
        return LOCKS[(int) (Math.abs(id) % LOCKS.length)];
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
