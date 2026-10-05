package org.dual.replicate.app.training.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.UploadReport;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.Paged;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Use case delle bozze di dataset di addestramento. NON e' {@code @Transactional} a livello di metodo, come {@code ChatService}: i file dello storage non
 * sono transazionali, e il salvataggio atomico di bozza e immagini lo da' gia' lo store. L'ordine e' sempre "file prima, riga dopo" in scrittura e
 * "riga prima, file dopo" in cancellazione, con la pulizia dei file appena scritti se la riga non si salva: un guasto lascia al peggio un file orfano,
 * mai una riga che punta a un file mancante.
 */
@Service
public class TrainingDatasetService implements ITrainingDatasets {

    private final ITrainingDatasetStore store;
    private final IImageStorageService storage;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final Clock clock;
    private final int maxImages;

    @Autowired
    public TrainingDatasetService(ITrainingDatasetStore store, IImageStorageService storage, ISystemEvents systemEvents, Messages messages,
                                  @Value("${app.training.max-images:25}") int maxImages) {
        this(store, storage, systemEvents, messages, Clock.systemDefaultZone(), maxImages);
    }

    TrainingDatasetService(ITrainingDatasetStore store, IImageStorageService storage, ISystemEvents systemEvents, Messages messages, Clock clock,
                           int maxImages) {
        this.store = store;
        this.storage = storage;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.clock = clock;
        this.maxImages = maxImages;
    }

    @Override
    public int maxImages() {
        return maxImages;
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
        return find(id).orElseThrow(() -> new TrainingException(messages.get("training.error.notFound")));
    }

    @Override
    public TrainingDataset create(String name, String triggerWord, LoraType loraType, String note) {
        return store.save(new TrainingDataset(validName(name), validTriggerWord(triggerWord), validType(loraType), optionalNote(note), clock.instant()));
    }

    @Override
    public TrainingDataset update(Long id, String name, String triggerWord, LoraType loraType, String note) {
        TrainingDataset dataset = editable(id);
        dataset.update(validName(name), validTriggerWord(triggerWord), validType(loraType), optionalNote(note), clock.instant());
        return save(dataset);
    }

    @Override
    public TrainingDataset duplicate(Long id) {
        TrainingDataset source = get(id);
        Instant now = clock.instant();
        TrainingDataset copy = new TrainingDataset(copyName(source.getName()), source.getTriggerWord(), source.getLoraType(), source.getNote(),
                false, source.getId(), now);
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
            copied.forEach(this::deleteQuietly);
            throw e;
        }
    }

    @Override
    public void delete(Long id) {
        TrainingDataset dataset = editable(id);
        store.delete(dataset);
        dataset.ownedFilenames().forEach(this::deleteQuietly);
    }

    @Override
    public UploadReport addImages(Long datasetId, List<UploadedFile> files) {
        TrainingDataset dataset = editable(datasetId);
        int room = maxImages - dataset.getImages().size();
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
                    systemEvents.record("storeTrainingImage", e);
                }
                entries.add(Entry.rejected(name, e.isReportable() ? messages.get("training.error.saveFailed", name) : e.getMessage()));
            }
        }
        List<String> storedFiles = entries.stream().map(Entry::filename).filter(f -> f != null).toList();
        Map<String, Long> idByFilename = new HashMap<>();
        if (!storedFiles.isEmpty()) {
            Instant now = clock.instant();
            entries.stream().filter(e -> e.filename() != null).forEach(e -> dataset.addImage(e.filename(), e.name(), now));
            try {
                store.save(dataset).getImages().forEach(image -> idByFilename.put(image.getFilename(), image.getId()));
            } catch (RuntimeException e) {
                storedFiles.forEach(this::deleteQuietly);
                throw translated(e);
            }
        }
        return new UploadReport(entries.stream()
                .map(e -> e.filename() == null ? UploadReport.Result.rejected(e.name(), e.rejection())
                        : UploadReport.Result.accepted(e.name(), idByFilename.get(e.filename())))
                .toList());
    }

    @Override
    public void removeImage(Long datasetId, Long imageId) {
        TrainingDataset dataset = editable(datasetId);
        TrainingImage removed = dataset.removeImage(imageId, clock.instant())
                .orElseThrow(() -> new TrainingException(messages.get("training.error.imageNotFound")));
        save(dataset);
        removed.ownedFilenames().forEach(this::deleteQuietly);
    }

    // --- interno ------------------------------------------------------------------------------------------------

    /** La bozza da modificare: un id sconosciuto o uno snapshot (sola lettura) e' un rifiuto. */
    private TrainingDataset editable(Long id) {
        TrainingDataset dataset = get(id);
        if (dataset.isFrozen()) {
            throw new TrainingException(messages.get("training.error.frozen"));
        }
        return dataset;
    }

    private TrainingDataset save(TrainingDataset dataset) {
        try {
            return store.save(dataset);
        } catch (RuntimeException e) {
            throw translated(e);
        }
    }

    /** Una copia vecchia sovrascritta (blocco ottimistico) e' un conflitto da mostrare, non un errore interno. */
    private RuntimeException translated(RuntimeException e) {
        return e instanceof OptimisticLockingFailureException ? new TrainingException(messages.get("training.error.conflict")) : e;
    }

    private String copyFile(String filename, List<String> copied) {
        String copy = storage.copy(filename);
        copied.add(copy);
        return copy;
    }

    /** Un file che non si riesce a eliminare resta orfano: registrato, ma non fa fallire l'operazione gia' riuscita sulla riga. */
    private void deleteQuietly(String filename) {
        try {
            storage.delete(filename);
        } catch (RuntimeException e) {
            systemEvents.record("deleteTrainingFile", e);
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
