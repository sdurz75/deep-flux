package org.dual.replicate.app.training.application;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.dual.replicate.app.prompt.domain.CaptionStyle;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.app.prompt.domain.ImageCaptionException;
import org.dual.replicate.app.prompt.port.in.IImageCaptioner;
import org.dual.replicate.app.training.domain.CaptionSource;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.PendingCaption;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.event.CaptionRequestedEvent;
import org.dual.replicate.app.training.port.in.ICaptionJobs;
import org.dual.replicate.app.training.port.out.ITrainingDatasetStore;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Il lavoro che scrive la didascalia automatica di un'immagine con un modello di visione ({@link IImageCaptioner}). Gira in background e la chiamata al
 * modello dura secondi, durante i quali l'immagine puo' essere stata eliminata, ritagliata di nuovo o riscritta a mano: per questo il risultato si scrive
 * con {@link TrainingImage#applyAutoCaption}, che lo scarta se l'immagine non e' piu' quella (stesso file, ancora in sospeso, non a mano). Nessuno stato
 * indefinito: o la didascalia arriva, o l'immagine diventa {@code FAILED} (si riprova a mano); un riavvio a meta' lo ripristina {@link #recoverPending}.
 */
@Service
public class CaptionJobService implements ICaptionJobs {

    private static final Logger log = LoggerFactory.getLogger(CaptionJobService.class);

    private final DatasetEditor editor;
    private final ITrainingDatasetStore store;
    private final IImageStorageService storage;
    private final IImageCaptioner captioner;
    private final ISystemEvents systemEvents;
    private final ApplicationEventPublisher eventPublisher;

    /** Lavori in corso in questo processo: evita che un evento doppio (o lo sweep) ne faccia partire due sulla stessa immagine, cioe' due chiamate a pagamento. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public CaptionJobService(DatasetEditor editor, ITrainingDatasetStore store, IImageStorageService storage, IImageCaptioner captioner,
                             ISystemEvents systemEvents, ApplicationEventPublisher eventPublisher) {
        this.editor = editor;
        this.store = store;
        this.storage = storage;
        this.captioner = captioner;
        this.systemEvents = systemEvents;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void caption(Long datasetId, Long imageId) {
        if (datasetId == null || imageId == null || !inFlight.add(imageId)) {
            return;
        }
        try {
            run(datasetId, imageId);
        } catch (TrainingException e) {
            // Bozza sparita o congelata, immagine tolta, conflitto che non si e' risolto: niente da scrivere. Se l'immagine e' ancora in sospeso lo sweep la riprende.
            if (e.isReportable()) {
                systemEvents.record("captionTrainingImage", e, AppEventSubjects.ofTrainingDataset(datasetId));
            } else {
                log.debug("Didascalia dell'immagine {} non scritta: {}", imageId, e.getMessage());
            }
        } catch (RuntimeException e) {
            // Mai propagare: gira in un thread in background. Una bozza sparita nel frattempo (test, eliminazione) non e' un errore da registrare.
            if (store.findById(datasetId).isPresent()) {
                recordFailure(e, datasetId);
            }
        } finally {
            inFlight.remove(imageId);
        }
    }

    private void run(Long datasetId, Long imageId) {
        TrainingDataset dataset = store.findById(datasetId).orElse(null);
        if (dataset == null || dataset.isFrozen()) {
            return;
        }
        TrainingImage image = dataset.findImage(imageId).orElse(null);
        if (image == null || !image.isCaptionPending() || image.getCaptionSource() == CaptionSource.MANUAL) {
            return; // idempotente: gia' scritta, non piu' richiesta o diventata dell'utente
        }
        // Si ricorda QUALE file si didascalizza: se nel frattempo viene ritagliato di nuovo, il risultato non vale piu' per quello attuale.
        String filename = image.getFilename();
        String text = captionOrNull(dataset, filename);
        editor.mutate(datasetId, current -> current.findImage(imageId).ifPresent(i -> {
            if (text != null) {
                i.applyAutoCaption(filename, text);
            } else {
                i.failAutoCaption(filename);
            }
        }));
    }

    /** La didascalia, o {@code null} se non e' stata prodotta (rifiuto del modello: atteso, senza evento; guasto vero: registrato). */
    private String captionOrNull(TrainingDataset dataset, String filename) {
        try {
            SourceImage source = storage.read(filename);
            return captioner.caption(source, dataset.getTriggerWord(), styleOf(dataset.getLoraType()));
        } catch (ImageCaptionException e) {
            log.info("Didascalia di {} non prodotta: {}", filename, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            if (!(e instanceof RemoteServiceException remote) || remote.isReportable()) {
                recordFailure(e, dataset.getId());
            }
            return null;
        }
    }

    /** Registrato sul dataset (subject): i guasti di dataset diversi non si fondono in una serie e hanno il link "apri". Un guasto non remoto e' del training, non "interno". */
    private void recordFailure(RuntimeException e, Long datasetId) {
        String subject = AppEventSubjects.ofTrainingDataset(datasetId);
        if (e instanceof RemoteServiceException) {
            systemEvents.record("captionTrainingImage", e, subject);
        } else {
            systemEvents.record(AppEventSource.TRAINING, "captionTrainingImage", e, subject);
        }
    }

    private static CaptionStyle styleOf(LoraType type) {
        return type == LoraType.STYLE ? CaptionStyle.STYLE : CaptionStyle.SUBJECT;
    }

    @Override
    public int recoverPending() {
        List<PendingCaption> pending = store.findPendingCaptions();
        pending.forEach(p -> eventPublisher.publishEvent(new CaptionRequestedEvent(p.datasetId(), p.imageId())));
        return pending.size();
    }
}
