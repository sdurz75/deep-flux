package org.dual.hexa.app.training.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.TrainingException;
import org.dual.hexa.app.training.domain.TrainingImage;
import org.dual.hexa.app.training.domain.event.CaptionRequestedEvent;
import org.dual.hexa.app.training.port.in.ITrainingCaptions;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Le azioni dell'utente sulle didascalie. Ogni scrittura passa da {@link DatasetEditor} (rilettura e riapplicazione sui conflitti: le didascalie
 * automatiche arrivano in parallelo) e il lavoro in background parte SOLO a salvataggio riuscito, per le sole immagini che sono davvero diventate
 * {@code PENDING} nello stato salvato.
 */
@Service
public class TrainingCaptionService implements ITrainingCaptions {

    private final DatasetEditor editor;
    private final ApplicationEventPublisher eventPublisher;
    private final Messages messages;

    public TrainingCaptionService(DatasetEditor editor, ApplicationEventPublisher eventPublisher, Messages messages) {
        this.editor = editor;
        this.eventPublisher = eventPublisher;
        this.messages = messages;
    }

    @Override
    public TrainingDataset saveCaption(Long datasetId, Long imageId, String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.length() > MAX_CAPTION) {
            throw new TrainingException(messages.get("training.error.captionTooLong", MAX_CAPTION));
        }
        // Nessun touch: una didascalia non rimette la bozza in cima all'elenco, e la versione sale comunque (lo garantisce lo store).
        return editor.mutate(datasetId, dataset -> editor.image(dataset, imageId).writeCaption(clean));
    }

    @Override
    public TrainingDataset recaption(Long datasetId, Long imageId) {
        AtomicBoolean requested = new AtomicBoolean();
        TrainingDataset saved = editor.mutate(datasetId, dataset -> requested.set(editor.image(dataset, imageId).requestAutoCaption(true)));
        if (requested.get()) {
            request(datasetId, imageId);
        }
        return saved;
    }

    @Override
    public TrainingDataset recaptionAutomatic(Long datasetId) {
        AtomicReference<List<Long>> started = new AtomicReference<>(List.of());
        TrainingDataset saved = editor.mutate(datasetId, dataset -> {
            List<Long> ids = new ArrayList<>();
            for (TrainingImage image : dataset.getImages()) {
                // Una gia' in sospeso ha il suo lavoro in coda o in corso: non se ne accoda un secondo.
                boolean alreadyPending = image.isCaptionPending();
                if (image.requestAutoCaption(false) && !alreadyPending) {
                    ids.add(image.getId());
                }
            }
            started.set(ids);
        });
        started.get().forEach(imageId -> request(datasetId, imageId));
        return saved;
    }

    @Override
    public TrainingDataset addTriggerWord(Long datasetId) {
        return editor.mutate(datasetId, dataset -> dataset.getImages().forEach(image -> image.prependTriggerWord(dataset.getTriggerWord())));
    }

    private void request(Long datasetId, Long imageId) {
        eventPublisher.publishEvent(new CaptionRequestedEvent(datasetId, imageId));
    }
}
