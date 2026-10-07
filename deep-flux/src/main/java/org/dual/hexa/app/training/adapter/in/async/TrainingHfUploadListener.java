package org.dual.hexa.app.training.adapter.in.async;

import java.util.Locale;

import org.dual.hexa.app.training.domain.event.HfUploadRequestedEvent;
import org.dual.hexa.app.training.port.in.ITrainingHfUploads;
import org.springframework.context.event.EventListener;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Esegue in background il caricamento a mano dei pesi (scarica da Replicate, carica su HuggingFace). Un listener semplice, non transazionale: la richiesta non
 * scrive nulla prima dell'evento. La locale e' l'italiano, come per il risultato: il thread non ha quella della richiesta.
 */
@Component
public class TrainingHfUploadListener {

    private final ITrainingHfUploads uploads;

    public TrainingHfUploadListener(ITrainingHfUploads uploads) {
        this.uploads = uploads;
    }

    @Async(TrainingHfUploadExecutorConfig.EXECUTOR)
    @EventListener
    public void onUploadRequested(HfUploadRequestedEvent event) {
        LocaleContextHolder.setLocale(Locale.ITALIAN);
        try {
            uploads.upload(event.trainingId(), event.tokenId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
