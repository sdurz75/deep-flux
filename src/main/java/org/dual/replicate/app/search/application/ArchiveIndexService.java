package org.dual.replicate.app.search.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.IArchiveIndex;
import org.dual.replicate.app.search.port.in.ISearchableSource;
import org.dual.replicate.app.search.port.out.IVectorIndex;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

/**
 * Tiene l'indice semantico allineato ai dati, con una RICONCILIAZIONE idempotente invece di ganci su ogni {@code save}:
 * aggiunge i documenti mancanti o cambiati ({@link IVectorIndex} salta gli invariati per hash del testo e modello), rimuove quelli
 * la cui riga sorgente non esiste piu'. Cosa si indicizza lo decidono le {@link ISearchableSource} (oggi: prompt delle generazioni
 * riuscite, messaggi delle chat non di errore, titoli delle conversazioni). Chi la avvia (all'avvio, a intervalli, a fine
 * generazione) sta negli adapter. Un documento che non si riesce a indicizzare e' registrato ({@link ISystemEvents}) e non ferma gli altri.
 */
@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveIndexService implements IArchiveIndex {

    private final IVectorIndex index;
    private final List<ISearchableSource> sources;
    private final ISystemEvents systemEvents;
    /** Solo la LETTURA delle sorgenti (JPA) e' in transazione (read-only): le scritture sull'indice vanno fuori, vedi {@link #reconcile()}. */
    private final TransactionTemplate readOnly;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "archive-indexer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean rerun = new AtomicBoolean();

    public ArchiveIndexService(IVectorIndex index, List<ISearchableSource> sources, ISystemEvents systemEvents,
                               PlatformTransactionManager transactionManager) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.index = index;
        this.sources = sources;
        this.systemEvents = systemEvents;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void reindexAsync() {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        executor.execute(() -> {
            try {
                do {
                    rerun.set(false);
                    reconcile();
                } while (rerun.get());
            } finally {
                running.set(false);
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean reembed(String id) {
        return index.reembed(id);
    }

    /**
     * La lettura delle sorgenti sta in una transazione read-only (accesso lazy alle associazioni); le scritture sull'indice
     * (stesso DataSource, quindi stessa connessione se fossero nella transazione) NO: in una transazione read-only Postgres
     * rifiuta gli INSERT e, dopo il primo errore, scarterebbe ogni comando successivo, vanificando il "un documento difettoso
     * non ferma gli altri".
     */
    @Override
    public void reconcile() {
        try {
            Map<String, SearchableDocument> wanted = readOnly.execute(status -> wantedDocuments());
            Set<String> stale = new HashSet<>();
            for (ISearchableSource source : sources) {
                for (String type : source.types()) {
                    stale.addAll(index.idsOfType(type));
                }
            }
            stale.removeAll(wanted.keySet());
            if (!stale.isEmpty()) {
                index.delete(new ArrayList<>(stale));
            }
            addAll(new ArrayList<>(wanted.values()));
            stampNotes();
        } catch (RuntimeException e) {
            systemEvents.record("reindex", e);
        }
    }

    /**
     * Le note nascono con {@code createdAt}; quelle precedenti al filtro per periodo non lo hanno: per le note {@code refId} e' il
     * millisecondo di creazione. Si riscrivono i soli metadata (testo e modello invariati: l'indice non ri-embedda).
     */
    private void stampNotes() {
        for (String id : index.idsOfType(DocumentTypes.NOTE)) {
            index.find(id).filter(doc -> !doc.metadata().containsKey("createdAt")).ifPresent(doc -> {
                Map<String, Object> metadata = new HashMap<>(doc.metadata());
                metadata.put("createdAt", doc.refId());
                index.upsertIfChanged(List.of(new SearchableDocument(doc.id(), doc.content(), metadata)));
            });
        }
    }

    private void addAll(List<SearchableDocument> toIndex) {
        for (int from = 0; from < toIndex.size(); from += 32) {
            List<SearchableDocument> batch = toIndex.subList(from, Math.min(toIndex.size(), from + 32));
            try {
                index.upsertIfChanged(batch);
            } catch (RuntimeException batchFailure) {
                // Un documento difettoso non deve bloccare gli altri: si ripete uno per uno.
                for (SearchableDocument document : batch) {
                    try {
                        index.upsertIfChanged(List.of(document));
                    } catch (RuntimeException e) {
                        systemEvents.record("reindex", e);
                    }
                }
            }
        }
    }

    private Map<String, SearchableDocument> wantedDocuments() {
        Map<String, SearchableDocument> wanted = new HashMap<>();
        for (ISearchableSource source : sources) {
            for (SearchableDocument document : source.documents()) {
                if (document.text() == null || document.text().isBlank()) {
                    continue;
                }
                String trimmed = document.text().strip();
                wanted.put(document.id(), new SearchableDocument(document.id(),
                        trimmed.length() > DocumentTypes.MAX_CHARS ? trimmed.substring(0, DocumentTypes.MAX_CHARS) : trimmed,
                        document.metadata()));
            }
        }
        return wanted;
    }
}
