package org.hexa.app.training.adapter.in.async;

import java.util.Locale;

import org.hexa.app.training.domain.event.TrainingCompletedEvent;
import org.hexa.app.training.port.in.ITrainingResults;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Completa in background il risultato di un training riuscito (preset, modello utilizzabile, copia su HuggingFace verificata). Dopo il commit se c'e' una
 * transazione (la riga deve esserci quando il lavoro la rilegge), subito se no ({@code fallbackExecution}); su un executor dedicato, perche' parla con Replicate e
 * HuggingFace. La locale e' l'italiano, come il recupero: il thread non ha quella di una richiesta.
 */
@Component
public class TrainingResultListener {

    private final ITrainingResults results;

    public TrainingResultListener(ITrainingResults results) {
        this.results = results;
    }

    @Async(TrainingResultExecutorConfig.EXECUTOR)
    @TransactionalEventListener(fallbackExecution = true)
    public void onTrainingCompleted(TrainingCompletedEvent event) {
        LocaleContextHolder.setLocale(Locale.ITALIAN);
        try {
            results.complete(event.trainingId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
