package org.dual.replicate.app.generation.port.in;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.core.kernel.Paged;

/**
 * Le generazioni (immagini e video): creazione su Replicate, avanzamento dello stato fino al download, cancellazione, preferiti,
 * e le viste di archivio (galleria, listato). Chi sta fuori dalla feature (chat, ricerca) passa solo da qui.
 */
public interface IGenerations {

    /**
     * Richiesta di una nuova generazione. {@code parameters} sono i parametri del modello gia' tipizzati (Long/Double/Boolean/
     * String), nel vocabolario del provider; i campi di una form HTML li converte l'interface layer, non questa porta.
     * {@code sourceGenerationId}+{@code sourceImage} o {@code sourceUploadFilename} (upload, ha la precedenza) sono
     * l'immagine sorgente, ignorata dai modelli che non ne prendono una.
     */
    record CreateCommand(String model, String version, String prompt, Map<String, Object> parameters,
                         Long sourceGenerationId, String sourceImage, String sourceUploadFilename) {

        /** Generazione senza sorgente (il tool della chat). */
        public static CreateCommand of(String model, String version, String prompt, Map<String, Object> parameters) {
            return new CreateCommand(model, version, prompt, parameters, null, null, null);
        }
    }

    /**
     * Avvia una generazione. Kind, chiave dell'immagine sorgente e obbligo della sorgente (modelli di modifica: una sorgente
     * assente e' un errore prima di chiamare il provider) li decide il form-type del modello, non il chiamante.
     */
    Generation create(CreateCommand command);

    /** Fa avanzare lo stato interrogando il provider (scarica se pronta, fallisce se scaduta o cancellata). */
    Generation refresh(Long id);

    /** True se la generazione, ancora non terminale, ha superato il proprio timeout di business (image/video). */
    boolean isOverdue(Generation generation);

    /** Attende (poll) che la generazione diventi terminale o scada {@code timeout}; ritorna l'ultimo stato. */
    Generation waitUntilTerminal(Long id, Duration timeout);

    /** Interrompe una generazione in corso e fa avanzare lo stato; se l'interruzione non riesce la generazione resta com'e'. */
    Generation cancel(Long id);

    /** Associa una generazione avviata da una conversazione alla conversazione (ripristino del placeholder al reload). */
    void attachToConversation(Long id, Long conversationId);

    /** Scollega dalla conversazione cancellata tutte le sue generazioni (che restano in archivio): niente FK verso la chat nello schema. */
    void detachFromConversation(Long conversationId);

    boolean exists(Long id);

    /** @throws org.dual.replicate.app.generation.domain.ReplicateException REJECTED se non esiste */
    Generation get(Long id);

    Optional<Generation> find(Long id);

    /** Le generazioni esistenti fra gli id dati (gli id cancellati sono semplicemente assenti). */
    List<Generation> findAllById(Collection<Long> ids);

    /** Generazioni ancora in corso (non scadute) avviate dalla conversazione indicata. */
    List<Generation> inProgressForConversation(Long conversationId);

    /** Tutte le generazioni PENDING/PROCESSING (recupero all'avvio e sweep). */
    List<Generation> inProgress();

    /** Tutte le generazioni RIUSCITE (per l'indice di ricerca). */
    List<Generation> succeeded();

    /** Galleria contestuale: le generazioni RIUSCITE di una conversazione, in ordine cronologico. */
    List<Generation> succeededForConversation(Long conversationId);

    /** Id (le piu' recenti, al massimo {@code limit}) delle generazioni terminali di una conversazione completate prima di {@code before}. */
    List<Long> terminalIdsWithConversation(Instant before, int limit);

    /** Galleria: tab "Tutte" (una card per generazione RIUSCITA, primo file), piu' recenti prima. */
    Paged<GalleryItem> galleryPage(int pageIndex, int pageSize);

    /** Galleria: tab "Preferiti" (una card per file con la star, di generazioni riuscite), piu' recenti prima. */
    Paged<GalleryItem> favouritesPage(int pageIndex, int pageSize);

    /** Listato di TUTTE le generazioni, qualunque stato, piu' recenti prima. */
    Paged<Generation> listPage(int pageIndex, int pageSize);

    void delete(Long id);

    void deleteAll(List<Long> ids);

    void deleteEverything();

    /** Elimina un file (e, se era l'ultimo, l'intera generazione): {@code true} se la generazione e' stata eliminata a cascata. */
    boolean deleteImage(Long generationId, String filename);

    /** Aggiunge/toglie la star a un file; ritorna il nuovo stato. */
    boolean toggleFavourite(Long generationId, String filename);
}
