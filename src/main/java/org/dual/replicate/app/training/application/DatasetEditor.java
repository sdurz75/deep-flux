package org.dual.replicate.app.training.application;

import java.util.Optional;
import java.util.function.Consumer;

import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * L'UNICO modo in cui l'app scrive su una bozza: carica, verifica che sia modificabile, applica la modifica, salva. Se nel frattempo qualcun altro ha
 * salvato (il dataset ha una versione: un'altra richiesta dell'utente, o una didascalia automatica appena arrivata) il salvataggio fallisce e si RILEGGE
 * e si RIAPPLICA la stessa modifica sullo stato nuovo, fino a {@link #MAX_ATTEMPTS} volte; solo allora e' un conflitto da mostrare all'utente. Le
 * didascalie in background rendono la scrittura concorrente la norma, non l'eccezione: senza i tentativi ogni caricamento mentre arrivano le
 * didascalie finirebbe in un errore.
 *
 * <p>La modifica ({@code change}) puo' quindi girare PIU' VOLTE, ogni volta su un dataset appena letto: deve dipendere solo da quello e dai suoi argomenti,
 * e se deve ricordare qualcosa per dopo (un file da eliminare) lo deve riscrivere a ogni giro. Se lancia, non si riprova: e' un rifiuto.
 */
@Component
class DatasetEditor {

    static final int MAX_ATTEMPTS = 3;

    private final ITrainingDatasetStore store;
    private final Messages messages;

    DatasetEditor(ITrainingDatasetStore store, Messages messages) {
        this.store = store;
        this.messages = messages;
    }

    /** Un dataset qualunque (anche congelato); un id sconosciuto e' un rifiuto, non un errore interno. */
    TrainingDataset get(Long id) {
        return (id == null ? Optional.<TrainingDataset>empty() : store.findById(id))
                .orElseThrow(() -> new TrainingException(messages.get("training.error.notFound")));
    }

    /** La bozza da modificare: un id sconosciuto o uno snapshot (sola lettura) e' un rifiuto. */
    TrainingDataset editable(Long id) {
        TrainingDataset dataset = get(id);
        if (dataset.isFrozen()) {
            throw new TrainingException(messages.get("training.error.frozen"));
        }
        return dataset;
    }

    /** L'immagine {@code imageId} di {@code dataset}; non e' sua (o non esiste) = rifiuto. */
    TrainingImage image(TrainingDataset dataset, Long imageId) {
        return dataset.findImage(imageId).orElseThrow(() -> new TrainingException(messages.get("training.error.imageNotFound")));
    }

    /** Applica {@code change} alla bozza {@code id} e la salva; ritorna la bozza salvata. */
    TrainingDataset mutate(Long id, Consumer<TrainingDataset> change) {
        for (int attempt = 1; ; attempt++) {
            TrainingDataset dataset = editable(id);
            change.accept(dataset);
            try {
                return store.save(dataset);
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new TrainingException(messages.get("training.error.conflict"));
                }
            }
        }
    }

    /** Elimina la bozza. Una copia vecchia (il dataset e' stato modificato dopo la lettura) e' un conflitto, come per un salvataggio. */
    void delete(TrainingDataset dataset) {
        try {
            store.delete(dataset);
        } catch (OptimisticLockingFailureException e) {
            throw new TrainingException(messages.get("training.error.conflict"));
        }
    }
}
