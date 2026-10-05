package org.dual.replicate.app.generation.adapter.in.async;

import java.util.Locale;

import org.dual.replicate.app.generation.domain.event.ImageImportedEvent;
import org.dual.replicate.app.generation.port.in.IImportedImages;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avvia in background l'analisi di contenuto di un'immagine appena importata. Dopo il commit (la riga deve esserci quando l'analisi la
 * rilegge); senza transazione in corso scatta subito ({@code fallbackExecution}, come {@code GenerationSearchSource}). Su un executor
 * dedicato (poche analisi alla volta: ognuna e' una chiamata a pagamento al modello di visione). La locale e' l'italiano, come il recupero.
 */
@Component
public class ImportAnalysisListener {

    private final IImportedImages importedImages;

    public ImportAnalysisListener(IImportedImages importedImages) {
        this.importedImages = importedImages;
    }

    @Async(ImportAnalysisExecutorConfig.EXECUTOR)
    @TransactionalEventListener(fallbackExecution = true)
    public void onImageImported(ImageImportedEvent event) {
        LocaleContextHolder.setLocale(Locale.ITALIAN);
        try {
            importedImages.analyze(event.generationId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
