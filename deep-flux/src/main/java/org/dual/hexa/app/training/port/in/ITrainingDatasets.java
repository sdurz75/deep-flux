package org.dual.hexa.app.training.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.app.training.domain.LaunchSettings;
import org.dual.hexa.app.training.domain.LoraType;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.UploadReport;
import org.dual.hexa.core.kernel.Paged;
import org.dual.hexa.core.storage.domain.UploadedFile;

/**
 * Le bozze di dataset di addestramento: CRUD, clone, immagini. La bozza e' lo stato intermedio persistente del caso d'uso "addestra un LoRA": si riprende
 * da dove era, si modifica e se ne lancia un nuovo training. Un dataset CONGELATO (snapshot di un training) si legge e si clona ma non si modifica.
 */
public interface ITrainingDatasets {

    int MAX_NAME = 80;
    int MAX_TRIGGER_WORD = 40;
    int MAX_NOTE = 500;
    int MAX_MODEL_NAME = 60;
    int MAX_HF_REPO_NAME = 96;
    /** Tetto di sanita' sul rettangolo di ritaglio (pixel dell'originale): il server non decodifica l'immagine, quindi non ne conosce le dimensioni vere. */
    int MAX_CROP_SIDE = 30_000;

    /** Quante immagini al massimo in un dataset (config {@code app.training.max-images}): il server e' l'autorita', la UI ripete il limite. */
    int maxImages();

    /** Passi di addestramento ammessi (config {@code app.training.min-steps|max-steps}): sono limiti dell'app, non del trainer. */
    int minSteps();

    int maxSteps();

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

    /**
     * Sostituisce il ritaglio di un'immagine. {@code cropped} e' il risultato prodotto dal browser (un'immagine vera, validata come ogni upload) e il
     * rettangolo e' in pixel dell'ORIGINALE: il server non decodifica nulla, lo tiene solo per riaprire l'editor dove era rimasto. L'originale non si
     * tocca mai; il ritaglio precedente si elimina.
     */
    TrainingDataset cropImage(Long datasetId, Long imageId, UploadedFile cropped, int x, int y, int width, int height);

    /** Torna all'immagine originale, senza ritaglio: elimina il file del ritaglio. */
    TrainingDataset resetCrop(Long datasetId, Long imageId);

    /** Salva le impostazioni di lancio della bozza (passi, seed, copia su HuggingFace...). I testi si ripuliscono; passi e seed fuori dai limiti sono un rifiuto. */
    TrainingDataset saveLaunchSettings(Long datasetId, LaunchSettings settings);

    /**
     * Congela una COPIA della bozza (con i file copiati) per un training: sola lettura, fuori dall'elenco delle bozze, con le stesse impostazioni di lancio.
     * La bozza non cambia. Chi lo chiama ne e' responsabile e lo elimina con {@link #deleteSnapshot} se il lancio non va a buon fine.
     */
    TrainingDataset snapshot(Long datasetId);

    /** Elimina uno snapshot (SOLO un dataset congelato: una bozza si elimina con {@link #delete}) e i suoi file. */
    void deleteSnapshot(Long snapshotId);
}
