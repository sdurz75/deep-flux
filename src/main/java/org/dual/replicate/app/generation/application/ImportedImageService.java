package org.dual.replicate.app.generation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.dual.replicate.app.generation.domain.AnalysisStatus;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.ImportReport;
import org.dual.replicate.app.generation.domain.ImportResult;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.replicate.app.generation.domain.event.ImageImportedEvent;
import org.dual.replicate.app.generation.port.in.IImportedImages;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.dual.replicate.app.prompt.domain.ImageAnalysisException;
import org.dual.replicate.app.prompt.domain.ImageDescription;
import org.dual.replicate.app.prompt.port.in.IImageDescriber;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Use case delle immagini esterne: salva ogni file (stesse regole dell'upload sorgente: magic bytes, dimensione, nome casuale), crea la
 * {@code Generation} IMPORTED (riuscita subito: visibile in galleria e usabile come sorgente) e analizza il contenuto con un modello di
 * visione ({@link IImageDescriber}) in background. L'esito dell'analisi sta nella riga ({@link AnalysisStatus}): nessuno stato indefinito, un
 * riavvio a meta' lo ripristina {@link #recoverPendingAnalyses}. Il push in galleria e la riconciliazione dell'indice di ricerca riusano
 * {@link GenerationCompletedEvent} (pubblicato all'importazione e a ogni fine analisi).
 */
@Service
public class ImportedImageService implements IImportedImages {

    private static final Logger log = LoggerFactory.getLogger(ImportedImageService.class);

    /** Oltre questa eta' una analisi PENDING non e' piu' "appena partita": lo sweep la riprende. */
    static final Duration PENDING_GRACE = Duration.ofMinutes(5);

    private final IGenerationStore store;
    private final IImageStorageService storage;
    private final IImageDescriber describer;
    private final ISystemEvents systemEvents;
    private final ApplicationEventPublisher eventPublisher;
    private final Messages messages;
    private final Clock clock;
    private final int maxFiles;

    /** Analisi in corso in questo processo: evita che listener e sweep ne facciano partire due sulla stessa riga. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public ImportedImageService(IGenerationStore store, IImageStorageService storage, IImageDescriber describer, ISystemEvents systemEvents,
                                ApplicationEventPublisher eventPublisher, Messages messages,
                                @Value("${app.import.max-files:20}") int maxFiles) {
        this(store, storage, describer, systemEvents, eventPublisher, messages, maxFiles, Clock.systemDefaultZone());
    }

    ImportedImageService(IGenerationStore store, IImageStorageService storage, IImageDescriber describer, ISystemEvents systemEvents,
                         ApplicationEventPublisher eventPublisher, Messages messages, int maxFiles, Clock clock) {
        this.store = store;
        this.storage = storage;
        this.describer = describer;
        this.systemEvents = systemEvents;
        this.eventPublisher = eventPublisher;
        this.messages = messages;
        this.maxFiles = maxFiles;
        this.clock = clock;
    }

    @Override
    public int maxFiles() {
        return maxFiles;
    }

    @Override
    public ImportReport importImages(List<UploadedFile> files) {
        List<ImportResult> results = new ArrayList<>();
        int considered = 0;
        for (UploadedFile file : files) {
            if (file == null || file.size() == 0) {
                continue; // un campo file non compilato: il browser invia comunque una parte vuota
            }
            String name = displayName(file);
            if (++considered > maxFiles) {
                results.add(ImportResult.rejected(name, messages.get("import.error.tooManyFiles", maxFiles)));
                continue;
            }
            results.add(importOne(file, name));
        }
        return new ImportReport(results);
    }

    private ImportResult importOne(UploadedFile file, String name) {
        String filename;
        try {
            filename = storage.storeUpload(file);
        } catch (RemoteServiceException e) {
            if (e.isReportable()) {
                systemEvents.record("importImage", e);
            }
            return ImportResult.rejected(name, e.getMessage());
        }
        Generation saved;
        try {
            saved = store.save(Generation.imported(filename, clock.instant()));
        } catch (RuntimeException e) {
            deleteQuietly(filename, e);
            systemEvents.record(CoreEventSource.INTERNAL, "importImage", e);
            return ImportResult.rejected(name, messages.get("import.error.saveFailed"));
        }
        eventPublisher.publishEvent(new GenerationCompletedEvent(saved));
        eventPublisher.publishEvent(new ImageImportedEvent(saved.getId()));
        return ImportResult.accepted(name, saved.getId(), filename);
    }

    @Override
    public void analyze(Long generationId) {
        if (generationId == null || !inFlight.add(generationId)) {
            return;
        }
        try {
            Generation generation = store.findById(generationId).orElse(null);
            if (generation == null || !generation.isImported() || generation.getAnalysisStatus() != AnalysisStatus.PENDING) {
                return;
            }
            runAnalysis(generation);
        } catch (RuntimeException e) {
            // Mai propagare: gira in un thread in background. Una riga sparita nel frattempo non e' un errore.
            if (store.existsById(generationId)) {
                systemEvents.record("analyzeImportedImage", e, AppEventSubjects.of(generationId, null));
            }
        } finally {
            inFlight.remove(generationId);
        }
    }

    private void runAnalysis(Generation generation) {
        try {
            SourceImage image = storage.read(generation.getImageFilenames().get(0));
            ImageDescription description = describer.describe(image);
            generation.applyAnalysis(description.description(), description.tags());
        } catch (ImageAnalysisException e) {
            // Rifiuto o risposta illeggibile: un esito atteso, senza toast. L'immagine resta valida e l'analisi si puo' ritentare.
            log.info("Analisi dell'immagine importata {} non riuscita: {}", generation.getId(), e.getMessage());
            generation.failAnalysis(messages.get("import.error.analysisRefused"));
        } catch (RuntimeException e) {
            if (!(e instanceof RemoteServiceException remote) || remote.isReportable()) {
                systemEvents.record("analyzeImportedImage", e, AppEventSubjects.of(generation.getId(), null));
            }
            generation.failAnalysis(messages.get("import.error.analysisFailed"));
        }
        Generation saved = store.save(generation);
        // Indice di ricerca e galleria si aggiornano come a ogni generazione completata.
        eventPublisher.publishEvent(new GenerationCompletedEvent(saved));
    }

    @Override
    public void retryAnalysis(Long generationId) {
        Generation generation = generationId == null ? null : store.findById(generationId).orElse(null);
        if (generation == null || !generation.isImported()) {
            throw new ReplicateException(messages.get("import.error.notImported"));
        }
        if (generation.getAnalysisStatus() == AnalysisStatus.PENDING) {
            return; // gia' da fare o in corso
        }
        generation.restartAnalysis();
        store.save(generation);
        eventPublisher.publishEvent(new ImageImportedEvent(generationId));
    }

    @Override
    public int recoverPendingAnalyses(boolean startup) {
        List<Generation> pending = startup
                ? store.findByAnalysisStatus(AnalysisStatus.PENDING)
                : store.findByAnalysisStatusAndCreatedAtBefore(AnalysisStatus.PENDING, Instant.now(clock).minus(PENDING_GRACE));
        pending.forEach(generation -> eventPublisher.publishEvent(new ImageImportedEvent(generation.getId())));
        return pending.size();
    }

    /** Il nome mostrato nell'esito: quello del client, o un segnaposto se manca (mai usato per salvare). */
    private String displayName(UploadedFile file) {
        String name = file.originalFilename();
        return name == null || name.isBlank() ? messages.get("import.result.unnamed") : name;
    }

    private void deleteQuietly(String filename, RuntimeException cause) {
        try {
            storage.delete(filename);
        } catch (RuntimeException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
    }
}
