package org.dual.replicate.app.training.application;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.dual.replicate.app.training.domain.PendingCaption;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Store in memoria per i test degli use case. Fedele su cio' che conta per le scritture concorrenti: legge e salva COPIE (chi modifica una bozza letta non
 * tocca lo stato salvato finche' non salva) e ogni salvataggio confronta e fa salire la versione, come il blocco ottimistico vero: una copia letta prima
 * che un altro salvasse fallisce con {@link OptimisticLockingFailureException}. Senza questo un nuovo tentativo riapplicherebbe la modifica sopra la
 * modifica gia' fatta sulla stessa istanza, e i test proverebbero un comportamento che in produzione non esiste.
 */
final class InMemoryDatasetStore implements ITrainingDatasetStore {

    /** Lo stato SALVATO (mai le istanze date a chi chiama). */
    final Map<Long, TrainingDataset> rows = new LinkedHashMap<>();
    RuntimeException failOnSave;
    /** Quante volte la prossima serie di salvataggi trova una copia vecchia a prescindere dalla versione (poi riesce). */
    int conflictsToThrow;
    /** Cosa e' successo "nel frattempo" quando un conflitto forzato scatta. */
    Runnable onConflict;
    /** Una scrittura "di un altro" che avviene fra la lettura e il PROSSIMO salvataggio (che quindi trova la versione cambiata). */
    Runnable beforeNextSave;
    int saveAttempts;
    private long nextId = 1;
    private long nextImageId = 1;

    @Override
    public TrainingDataset save(TrainingDataset dataset) {
        if (failOnSave != null) {
            throw failOnSave;
        }
        saveAttempts++;
        if (beforeNextSave != null) {
            Runnable other = beforeNextSave;
            beforeNextSave = null;
            other.run();
        }
        if (conflictsToThrow > 0) {
            conflictsToThrow--;
            if (onConflict != null) {
                onConflict.run();
            }
            throw new OptimisticLockingFailureException("versione vecchia");
        }
        TrainingDataset persisted = dataset.getId() == null ? null : rows.get(dataset.getId());
        if (persisted != null && versionOf(persisted) != versionOf(dataset)) {
            throw new OptimisticLockingFailureException("versione vecchia");
        }
        if (dataset.getId() == null) {
            ReflectionTestUtils.setField(dataset, "id", nextId++);
        }
        for (TrainingImage image : dataset.getImages()) {
            if (image.getId() == null) {
                ReflectionTestUtils.setField(image, "id", nextImageId++);
            }
        }
        ReflectionTestUtils.setField(dataset, "version", persisted == null ? 0L : versionOf(persisted) + 1);
        rows.put(dataset.getId(), copyOf(dataset));
        return copyOf(dataset);
    }

    @Override
    public Optional<TrainingDataset> findById(Long id) {
        return Optional.ofNullable(rows.get(id)).map(InMemoryDatasetStore::copyOf);
    }

    @Override
    public Paged<TrainingDataset> findDraftsPage(int pageIndex, int pageSize) {
        List<TrainingDataset> drafts = new ArrayList<>(rows.values().stream().filter(d -> !d.isFrozen()).map(InMemoryDatasetStore::copyOf).toList());
        // Stessa data per tutte (orologio fisso): a parita' di istante vince l'id piu' alto, come nella query vera.
        drafts.sort((a, b) -> b.getId().compareTo(a.getId()));
        return new Paged<>(drafts, pageIndex, pageSize, drafts.size());
    }

    @Override
    public List<PendingCaption> findPendingCaptions() {
        List<PendingCaption> pending = new ArrayList<>();
        rows.values().stream().filter(d -> !d.isFrozen())
                .forEach(d -> d.getImages().stream().filter(TrainingImage::isCaptionPending)
                        .forEach(i -> pending.add(new PendingCaption(d.getId(), i.getId()))));
        return pending;
    }

    @Override
    public void delete(TrainingDataset dataset) {
        TrainingDataset persisted = rows.get(dataset.getId());
        if (persisted != null && versionOf(persisted) != versionOf(dataset)) {
            throw new OptimisticLockingFailureException("versione vecchia");
        }
        rows.remove(dataset.getId());
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }

    // --- aiuti dei test -----------------------------------------------------------------------------------------

    /** Una scrittura di un altro processo sulla bozza salvata: ne cambia lo stato e fa salire la versione, come un salvataggio riuscito. */
    void concurrently(Long datasetId, Consumer<TrainingDataset> change) {
        TrainingDataset persisted = rows.get(datasetId);
        change.accept(persisted);
        for (TrainingImage image : persisted.getImages()) {
            if (image.getId() == null) {
                ReflectionTestUtils.setField(image, "id", nextImageId++);
            }
        }
        ReflectionTestUtils.setField(persisted, "version", versionOf(persisted) + 1);
    }

    /** L'immagine SALVATA (non una copia): per preparare uno stato di partenza o leggere l'esito senza passare dal servizio. */
    TrainingImage image(Long datasetId, Long imageId) {
        return rows.get(datasetId).findImage(imageId).orElseThrow();
    }

    private static long versionOf(TrainingDataset dataset) {
        return (long) ReflectionTestUtils.getField(dataset, "version");
    }

    private static TrainingDataset copyOf(TrainingDataset source) {
        TrainingDataset copy = BeanUtils.instantiateClass(TrainingDataset.class);
        copyFields(TrainingDataset.class, source, copy, "images");
        List<TrainingImage> images = new ArrayList<>();
        for (TrainingImage image : source.getImages()) {
            TrainingImage imageCopy = BeanUtils.instantiateClass(TrainingImage.class);
            copyFields(TrainingImage.class, image, imageCopy, "dataset");
            ReflectionTestUtils.setField(imageCopy, "dataset", copy);
            images.add(imageCopy);
        }
        ReflectionTestUtils.setField(copy, "images", images);
        return copy;
    }

    private static void copyFields(Class<?> type, Object from, Object to, String skip) {
        ReflectionUtils.doWithFields(type, field -> {
            ReflectionUtils.makeAccessible(field);
            field.set(to, field.get(from));
        }, field -> !Modifier.isStatic(field.getModifiers()) && !field.getName().equals(skip));
    }
}
