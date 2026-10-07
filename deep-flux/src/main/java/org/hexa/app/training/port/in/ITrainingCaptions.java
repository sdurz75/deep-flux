package org.hexa.app.training.port.in;

import org.hexa.app.training.domain.TrainingDataset;

/**
 * Le didascalie delle immagini di una bozza, dal punto di vista dell'utente: correggerle a mano, farle riscrivere, mettere la trigger word dove manca.
 * Quelle automatiche le scrive un lavoro in background ({@link ICaptionJobs}) e arrivano da sole; una scritta a mano non viene mai sovrascritta da un
 * lavoro automatico, solo da un "Rigenera" esplicito su quell'immagine.
 */
public interface ITrainingCaptions {

    /** Tetto di una didascalia scritta a mano. */
    int MAX_CAPTION = 1000;

    /** Salva la didascalia scritta (o corretta) a mano; vuota = nessuna didascalia. */
    TrainingDataset saveCaption(Long datasetId, Long imageId, String text);

    /** Rigenera la didascalia di UNA immagine, anche se era scritta a mano (e' una richiesta esplicita): torna in sospeso e parte il lavoro. */
    TrainingDataset recaption(Long datasetId, Long imageId);

    /** Rigenera le didascalie automatiche, quelle mancanti e quelle non riuscite; quelle scritte a mano restano come sono. */
    TrainingDataset recaptionAutomatic(Long datasetId);

    /** Mette la trigger word davanti alle didascalie che non la nominano (non crea didascalie dove mancano). */
    TrainingDataset addTriggerWord(Long datasetId);
}
