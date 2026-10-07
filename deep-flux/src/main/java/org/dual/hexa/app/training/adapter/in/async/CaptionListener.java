package org.dual.hexa.app.training.adapter.in.async;

import java.util.Locale;

import org.dual.hexa.app.training.domain.event.CaptionRequestedEvent;
import org.dual.hexa.app.training.port.in.ICaptionJobs;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avvia in background la didascalia automatica di un'immagine di una bozza. Dopo il commit (la riga deve esserci quando il lavoro la rilegge); senza
 * transazione in corso scatta subito ({@code fallbackExecution}, come gli altri listener dell'app). Su un executor dedicato. La locale e' l'italiano,
 * come il recupero.
 */
@Component
public class CaptionListener {

    private final ICaptionJobs jobs;

    public CaptionListener(ICaptionJobs jobs) {
        this.jobs = jobs;
    }

    @Async(CaptionExecutorConfig.EXECUTOR)
    @TransactionalEventListener(fallbackExecution = true)
    public void onCaptionRequested(CaptionRequestedEvent event) {
        LocaleContextHolder.setLocale(Locale.ITALIAN);
        try {
            jobs.caption(event.datasetId(), event.imageId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
