package ${package}.example.application;

import ${package}.example.domain.ExampleEventSource;
import ${package}.example.domain.ExampleItem;
import ${package}.example.port.in.IExamples;
import ${package}.example.port.out.IExampleStore;
import java.util.List;
import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.storage.domain.StorageException;
import org.hexa.core.storage.domain.UploadedFile;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usa due porte {@code in} delle librerie hexa: {@code IImageStorageService} (ogni binario passa da li') e {@code ISystemEvents} (ogni errore che si
 * ingoia si registra). Il subject {@code example:<id>} e' quello che {@code ExampleEventLinks} traduce in un link.
 */
@Service
public class ExampleService implements IExamples {

    private final IExampleStore store;
    private final IImageStorageService storage;
    private final ISystemEvents systemEvents;

    public ExampleService(IExampleStore store, IImageStorageService storage, ISystemEvents systemEvents) {
        this.store = store;
        this.storage = storage;
        this.systemEvents = systemEvents;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExampleItem> list() {
        return store.findNewestFirst();
    }

    @Override
    @Transactional
    public ExampleItem add(String title, UploadedFile attachment) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("title");
        }
        // Un upload non valido lancia StorageException REJECTED PRIMA di toccare il DB: nessuna riga, nessun file.
        String filename = attachment == null || attachment.size() == 0 ? null : storage.storeUpload(attachment);
        try {
            return store.save(new ExampleItem(trimmed, filename));
        } catch (RuntimeException e) {
            // Nessuno stato indefinito: se la riga non si salva, il file appena scritto non resta orfano.
            discard(filename, null);
            throw e;
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        store.findById(id).ifPresent(item -> {
            store.delete(item);
            discard(item.getAttachmentFilename(), id);
        });
    }

    /** Un file che non si cancella non deve far fallire l'operazione (la riga e' gia' andata): si registra, non si ingoia in silenzio. */
    private void discard(String filename, Long id) {
        if (filename == null) {
            return;
        }
        try {
            storage.delete(filename);
        } catch (StorageException e) {
            systemEvents.record(ExampleEventSource.EXAMPLE, "deleteAttachment", e, id == null ? null : "example:" + id);
        }
    }
}
