package org.hexa.app.training.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.hexa.app.training.domain.LaunchSettings;
import org.hexa.app.shared.domain.AppEventSubjects;
import org.hexa.app.training.domain.LoraType;
import org.hexa.app.training.domain.TrainingDataset;
import org.hexa.app.training.domain.TrainingException;
import org.hexa.app.training.domain.TrainingImage;
import org.hexa.app.training.domain.UploadReport;
import org.hexa.app.training.domain.event.CaptionRequestedEvent;
import org.hexa.app.training.port.in.ITrainingDatasets;
import org.hexa.app.training.port.out.ITrainingDatasetStore;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.kernel.Paged;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.storage.domain.StorageException;
import org.hexa.core.storage.domain.UploadedFile;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Use case delle bozze di dataset di addestramento. NON e' {@code @Transactional} a livello di metodo, come {@code ChatService}: i file dello storage non
 * sono transazionali, e il salvataggio atomico di bozza e immagini lo da' gia' lo store. L'ordine e' sempre "file prima, riga dopo" in scrittura e
 * "riga prima, file dopo" in cancellazione, con la pulizia dei file appena scritti se la riga non si salva: un guasto lascia al peggio un file orfano,
 * mai una riga che punta a un file mancante. Ogni scrittura sulla bozza passa da {@link DatasetEditor} (rilettura e riapplicazione sui conflitti di
 * versione: le didascalie automatiche scrivono in parallelo).
 */
@Service
public class TrainingDatasetService implements ITrainingDatasets {

    private final DatasetEditor editor;
    private final ITrainingDatasetStore store;
    private final IImageStorageService storage;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final int maxImages;
    private final int minSteps;
    private final int maxSteps;

    @Autowired
    public TrainingDatasetService(DatasetEditor editor, ITrainingDatasetStore store, IImageStorageService storage, ISystemEvents systemEvents,
                                  Messages messages, ApplicationEventPublisher eventPublisher, @Value("${app.training.max-images:25}") int maxImages,
                                  @Value("${app.training.min-steps:100}") int minSteps, @Value("${app.training.max-steps:4000}") int maxSteps) {
        this(editor, store, storage, systemEvents, messages, eventPublisher, Clock.systemDefaultZone(), maxImages, minSteps, maxSteps);
    }

    TrainingDatasetService(DatasetEditor editor, ITrainingDatasetStore store, IImageStorageService storage, ISystemEvents systemEvents,
                           Messages messages, ApplicationEventPublisher eventPublisher, Clock clock, int maxImages, int minSteps, int maxSteps) {
        this.editor = editor;
        this.store = store;
        this.storage = storage;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.maxImages = maxImages;
        this.minSteps = minSteps;
        this.maxSteps = maxSteps;
    }

    @Override
    public int maxImages() {
        return maxImages;
    }

    @Override
    public int minSteps() {
        return minSteps;
    }

    @Override
    public int maxSteps() {
        return maxSteps;
    }

    @Override
    public Paged<TrainingDataset> page(int pageIndex, int pageSize) {
        return store.findDraftsPage(pageIndex, pageSize);
    }

    @Override
    public Optional<TrainingDataset> find(Long id) {
        return id == null ? Optional.empty() : store.findById(id);
    }

    @Override
    public TrainingDataset get(Long id) {
        return editor.get(id);
    }

    @Override
    public TrainingDataset create(String name, String triggerWord, LoraType loraType, String note) {
        return store.save(new TrainingDataset(validName(name), validTriggerWord(triggerWord), validType(loraType), optionalNote(note), clock.instant()));
    }

    @Override
    public TrainingDataset update(Long id, String name, String triggerWord, LoraType loraType, String note) {
        // Si valida UNA volta, prima di leggere la bozza: il rifiuto non dipende da cio' che e' nel DB.
        String validName = validName(name);
        String validTrigger = validTriggerWord(triggerWord);
        LoraType validType = validType(loraType);
        String validNote = optionalNote(note);
        return editor.mutate(id, dataset -> dataset.update(validName, validTrigger, validType, validNote, clock.instant()));
    }

    @Override
    public TrainingDataset duplicate(Long id) {
        TrainingDataset source = editor.get(id);
        return copyOf(source, false, copyName(source.getName()));
    }

    @Override
    public TrainingDataset snapshot(Long datasetId) {
        TrainingDataset source = editor.editable(datasetId);
        return copyOf(source, true, source.getName());
    }

    /**
     * Una copia di {@code source} come nuovo dataset, con i file COPIATI (una riga possiede i suoi binari, nessuna dedup): sia il clone (bozza) sia lo snapshot
     * di un training (congelato) sono questa operazione. La provenienza e le impostazioni di lancio si portano dietro; se qualcosa fallisce a meta' i file gia'
     * copiati si eliminano e non si salva nulla.
     */
    private TrainingDataset copyOf(TrainingDataset source, boolean frozen, String name) {
        Instant now = clock.instant();
        TrainingDataset copy = new TrainingDataset(name, source.getTriggerWord(), source.getLoraType(), source.getNote(), frozen, source.getId(), now);
        copy.copyLaunchSettingsFrom(source);
        List<String> copied = new ArrayList<>();
        try {
            for (TrainingImage image : source.getImages()) {
                String filename = copyFile(image.getFilename(), copied);
                // Senza ritaglio i due nomi sono lo stesso file: se ne copia uno solo, come nell'originale.
                String original = image.getOriginalFilename().equals(image.getFilename()) ? filename : copyFile(image.getOriginalFilename(), copied);
                copy.addCopyOf(image, filename, original, now);
            }
            return store.save(copy);
        } catch (RuntimeException e) {
            copied.forEach(file -> deleteQuietly(file, source.getId()));
            throw e;
        }
    }

    @Override
    public void deleteSnapshot(Long snapshotId) {
        TrainingDataset snapshot = editor.get(snapshotId);
        if (!snapshot.isFrozen()) {
            throw new TrainingException(messages.get("training.error.notASnapshot"));
        }
        editor.delete(snapshot);
        snapshot.ownedFilenames().forEach(file -> deleteQuietly(file, snapshotId));
    }

    @Override
    public TrainingDataset saveLaunchSettings(Long datasetId, LaunchSettings settings) {
        LaunchSettings clean = validSettings(settings);
        return editor.mutate(datasetId, dataset -> dataset.applyLaunchSettings(clean, clock.instant()));
    }

    private LaunchSettings validSettings(LaunchSettings raw) {
        if (raw.trainingSteps() < minSteps || raw.trainingSteps() > maxSteps) {
            throw new TrainingException(messages.get("training.error.stepsOutOfRange", minSteps, maxSteps));
        }
        if (raw.seed() != null && (raw.seed() < 0 || raw.seed() > Integer.MAX_VALUE)) {
            throw new TrainingException(messages.get("training.error.seedInvalid", Integer.MAX_VALUE));
        }
        String modelName = blankToNull(raw.modelName());
        if (modelName != null && modelName.length() > MAX_MODEL_NAME) {
            throw new TrainingException(messages.get("training.error.modelNameTooLong", MAX_MODEL_NAME));
        }
        String repoName = blankToNull(raw.hfRepoName());
        if (repoName != null && repoName.length() > MAX_HF_REPO_NAME) {
            throw new TrainingException(messages.get("training.error.hfRepoNameTooLong", MAX_HF_REPO_NAME));
        }
        return new LaunchSettings(modelName, raw.trainingSteps(), raw.seed(), raw.hfPublish(), raw.hfTokenId(), repoName, raw.hfPrivate());
    }

    private static String blankToNull(String text) {
        String clean = text == null ? "" : text.strip();
        return clean.isEmpty() ? null : clean;
    }

    @Override
    public void delete(Long id) {
        TrainingDataset dataset = editor.editable(id);
        editor.delete(dataset);
        dataset.ownedFilenames().forEach(file -> deleteQuietly(file, id));
    }

    @Override
    public UploadReport addImages(Long datasetId, List<UploadedFile> files) {
        TrainingDataset current = editor.editable(datasetId);
        int room = maxImages - current.getImages().size();
        List<Entry> entries = new ArrayList<>();
        int accepted = 0;
        for (UploadedFile file : files) {
            if (file.size() == 0) {
                continue; // un <input type=file> senza scelta arriva come una parte vuota: non e' un file rifiutato
            }
            String name = displayName(file.originalFilename());
            if (accepted >= room) {
                entries.add(Entry.rejected(name, messages.get("training.error.tooManyImages", maxImages)));
                continue;
            }
            try {
                entries.add(Entry.stored(name, storage.storeUpload(file)));
                accepted++;
            } catch (StorageException e) {
                // Un rifiuto atteso (tipo, dimensione) ha gia' il suo messaggio; un guasto vero va nel registro e all'utente resta un messaggio generico.
                if (e.isReportable()) {
                    systemEvents.record("storeTrainingImage", e, AppEventSubjects.ofTrainingDataset(datasetId));
                }
                entries.add(Entry.rejected(name, e.isReportable() ? messages.get("training.error.saveFailed", name) : e.getMessage()));
            }
        }
        List<Entry> storedEntries = entries.stream().filter(e -> e.filename() != null).toList();
        Map<String, Long> idByFilename = new HashMap<>();
        if (!storedEntries.isEmpty()) {
            Instant now = clock.instant();
            try {
                editor.mutate(datasetId, dataset -> {
                    // Il posto si ricontrolla sullo stato riletto: un altro caricamento puo' averlo occupato fra la lettura e il salvataggio.
                    if (dataset.getImages().size() + storedEntries.size() > maxImages) {
                        throw new TrainingException(messages.get("training.error.tooManyImages", maxImages));
                    }
                    // Ogni immagine nuova parte con la didascalia automatica in sospeso.
                    storedEntries.forEach(e -> dataset.addImage(e.filename(), e.name(), now).requestAutoCaption(false));
                }).getImages().forEach(image -> idByFilename.put(image.getFilename(), image.getId()));
            } catch (RuntimeException e) {
                storedEntries.forEach(entry -> deleteQuietly(entry.filename(), datasetId));
                throw e;
            }
            storedEntries.forEach(e -> requestCaption(datasetId, idByFilename.get(e.filename())));
        }
        return new UploadReport(entries.stream()
                .map(e -> e.filename() == null ? UploadReport.Result.rejected(e.name(), e.rejection())
                        : UploadReport.Result.accepted(e.name(), idByFilename.get(e.filename())))
                .toList());
    }

    @Override
    public void removeImage(Long datasetId, Long imageId) {
        AtomicReference<TrainingImage> removed = new AtomicReference<>();
        editor.mutate(datasetId, dataset -> removed.set(dataset.removeImage(imageId, clock.instant())
                .orElseThrow(() -> new TrainingException(messages.get("training.error.imageNotFound")))));
        removed.get().ownedFilenames().forEach(file -> deleteQuietly(file, datasetId));
    }

    @Override
    public TrainingDataset cropImage(Long datasetId, Long imageId, UploadedFile cropped, int x, int y, int width, int height) {
        // Il rettangolo si controlla PRIMA di salvare qualunque file: un rifiuto non deve lasciare un orfano.
        if (x < 0 || y < 0 || width < 1 || height < 1 || x > MAX_CROP_SIDE || y > MAX_CROP_SIDE || width > MAX_CROP_SIDE || height > MAX_CROP_SIDE) {
            throw new TrainingException(messages.get("training.error.cropInvalid"));
        }
        // Prima di salvare il file si verifica che bozza e immagine esistano: un rifiuto non deve lasciare un orfano. Il vero controllo e' nel giro
        // di mutate, sullo stato riletto.
        editor.image(editor.editable(datasetId), imageId);
        String croppedFilename = storage.storeUpload(cropped);
        AtomicReference<Optional<String>> replaced = new AtomicReference<>(Optional.empty());
        AtomicBoolean recaption = new AtomicBoolean();
        TrainingDataset saved;
        try {
            saved = editor.mutate(datasetId, dataset -> {
                TrainingImage image = editor.image(dataset, imageId);
                replaced.set(image.applyCrop(croppedFilename, x, y, width, height));
                // La didascalia automatica descriveva un'altra inquadratura: va rifatta. Quella a mano e' dell'utente e resta.
                recaption.set(image.requestAutoCaption(false));
                dataset.touch(clock.instant());
            });
        } catch (RuntimeException e) {
            deleteQuietly(croppedFilename, datasetId);
            throw e;
        }
        replaced.get().ifPresent(file -> deleteQuietly(file, datasetId));
        if (recaption.get()) {
            requestCaption(datasetId, imageId);
        }
        return saved;
    }

    @Override
    public TrainingDataset resetCrop(Long datasetId, Long imageId) {
        if (!editor.image(editor.editable(datasetId), imageId).isCropped()) {
            return editor.get(datasetId);
        }
        AtomicReference<Optional<String>> replaced = new AtomicReference<>(Optional.empty());
        AtomicBoolean recaption = new AtomicBoolean();
        TrainingDataset saved = editor.mutate(datasetId, dataset -> {
            TrainingImage image = editor.image(dataset, imageId);
            replaced.set(image.clearCrop());
            recaption.set(image.requestAutoCaption(false));
            dataset.touch(clock.instant());
        });
        replaced.get().ifPresent(file -> deleteQuietly(file, datasetId));
        if (recaption.get()) {
            requestCaption(datasetId, imageId);
        }
        return saved;
    }

    // --- interno ------------------------------------------------------------------------------------------------

    /** Avvia in background la didascalia di un'immagine gia' salvata come {@code PENDING}. */
    private void requestCaption(Long datasetId, Long imageId) {
        eventPublisher.publishEvent(new CaptionRequestedEvent(datasetId, imageId));
    }

    private String copyFile(String filename, List<String> copied) {
        String copy = storage.copy(filename);
        copied.add(copy);
        return copy;
    }

    /** Un file che non si riesce a eliminare resta orfano: registrato, ma non fa fallire l'operazione gia' riuscita sulla riga. */
    private void deleteQuietly(String filename, Long datasetId) {
        try {
            storage.delete(filename);
        } catch (RuntimeException e) {
            systemEvents.record("deleteTrainingFile", e, AppEventSubjects.ofTrainingDataset(datasetId));
        }
    }

    /** "Nome (copia)": lo spazio e' qui e non nel bundle, perche' il parser delle properties scarta gli spazi iniziali di un valore. */
    private String copyName(String name) {
        String suffix = " " + messages.get("training.dataset.copySuffix");
        return (name.length() + suffix.length() > MAX_NAME ? name.substring(0, MAX_NAME - suffix.length()) : name) + suffix;
    }

    /** Il nome sul disco dell'utente, solo da mostrare: senza percorso (alcuni browser lo mandano), senza spazi ai bordi, mai vuoto. */
    private String displayName(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = slash >= 0 ? name.substring(slash + 1) : name;
        if (name.isEmpty()) {
            return messages.get("training.upload.unnamed");
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String validName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new TrainingException(messages.get("training.error.nameRequired"));
        }
        if (clean.length() > MAX_NAME) {
            throw new TrainingException(messages.get("training.error.nameTooLong", MAX_NAME));
        }
        return clean;
    }

    /** Una sola parola, senza spazi ne' virgole: il trainer la associa a tutte le immagini e finisce nei prompt. */
    private String validTriggerWord(String triggerWord) {
        String clean = triggerWord == null ? "" : triggerWord.strip();
        if (clean.isEmpty()) {
            throw new TrainingException(messages.get("training.error.triggerWordRequired"));
        }
        if (clean.length() > MAX_TRIGGER_WORD) {
            throw new TrainingException(messages.get("training.error.triggerWordTooLong", MAX_TRIGGER_WORD));
        }
        if (clean.chars().anyMatch(c -> Character.isWhitespace(c) || c == ',')) {
            throw new TrainingException(messages.get("training.error.triggerWordInvalid"));
        }
        return clean;
    }

    private LoraType validType(LoraType loraType) {
        if (loraType == null) {
            throw new TrainingException(messages.get("training.error.typeInvalid"));
        }
        return loraType;
    }

    private String optionalNote(String note) {
        String clean = note == null ? "" : note.strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() > MAX_NOTE) {
            throw new TrainingException(messages.get("training.error.noteTooLong", MAX_NOTE));
        }
        return clean;
    }

    /** Un file di un caricamento: salvato (con il nome nello storage) oppure rifiutato (con il motivo gia' tradotto). */
    private record Entry(String name, String filename, String rejection) {

        static Entry stored(String name, String filename) {
            return new Entry(name, filename, null);
        }

        static Entry rejected(String name, String rejection) {
            return new Entry(name, null, rejection);
        }
    }
}
