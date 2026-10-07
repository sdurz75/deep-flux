package ${package}.example.port.in;

import ${package}.example.domain.ExampleItem;
import java.util.List;
import org.dual.hexa.core.storage.domain.UploadedFile;

/** Cio' che la feature offre al mondo esterno (i controller parlano solo con le porte {@code in}). */
public interface IExamples {

    /** Le voci, dalla piu' recente. */
    List<ExampleItem> list();

    /**
     * Aggiunge una voce con un allegato facoltativo (immagine png/jpeg/webp, salvata da {@code IImageStorageService}). {@code attachment} puo' essere
     * {@code null} o vuoto.
     *
     * @throws IllegalArgumentException se il titolo e' vuoto
     * @throws org.dual.hexa.core.storage.domain.StorageException {@code REJECTED} se l'allegato non e' valido (tipo o dimensione): un esito atteso, solo un messaggio all'utente
     */
    ExampleItem add(String title, UploadedFile attachment);

    /** Elimina la voce e il suo allegato; una voce inesistente e' un no-op (idempotente). */
    void delete(Long id);
}
