package org.dual.replicate.app.training.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.UploadReport;
import org.dual.replicate.core.kernel.Paged;
import org.dual.replicate.core.storage.domain.UploadedFile;

/**
 * Le bozze di dataset di addestramento: CRUD, clone, immagini. La bozza e' lo stato intermedio persistente del caso d'uso "addestra un LoRA": si riprende
 * da dove era, si modifica e se ne lancia un nuovo training. Un dataset CONGELATO (snapshot di un training) si legge e si clona ma non si modifica.
 */
public interface ITrainingDatasets {

    int MAX_NAME = 80;
    int MAX_TRIGGER_WORD = 40;
    int MAX_NOTE = 500;

    /** Quante immagini al massimo in un dataset (config {@code app.training.max-images}): il server e' l'autorita', la UI ripete il limite. */
    int maxImages();

    /** Le bozze (non gli snapshot), l'ultima modificata prima. {@code pageIndex} parte da 0. */
    Paged<TrainingDataset> page(int pageIndex, int pageSize);

    /** Un dataset qualunque (anche congelato); vuoto se non esiste. */
    Optional<TrainingDataset> find(Long id);

    /** Come {@link #find} ma un id sconosciuto e' un rifiuto con messaggio. */
    TrainingDataset get(Long id);

    TrainingDataset create(String name, String triggerWord, LoraType loraType, String note);

    /** Modifica la configurazione di una bozza (non delle immagini). Un dataset congelato e' un rifiuto. */
    TrainingDataset update(Long id, String name, String triggerWord, LoraType loraType, String note);

    /**
     * Nuova bozza con le stesse immagini (i file sono COPIATI: ogni riga possiede i suoi), le didascalie e il ritaglio. Vale anche per un dataset congelato:
     * e' il "riprendi da questo training". La nuova bozza ricorda l'origine in {@link TrainingDataset#getSourceDatasetId()}.
     */
    TrainingDataset duplicate(Long id);

    /** Elimina una bozza e i suoi file. I training lanciati da lei non cambiano (hanno il loro snapshot). */
    void delete(Long id);

    /** Aggiunge immagini in coda. Un file rifiutato (tipo, dimensione, tetto del dataset) non ferma gli altri: l'esito e' per file. */
    UploadReport addImages(Long datasetId, List<UploadedFile> files);

    /** Toglie un'immagine dalla bozza ed elimina i suoi file. */
    void removeImage(Long datasetId, Long imageId);
}
