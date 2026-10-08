package org.dual.hexa.app.training.port.in;

import org.dual.hexa.app.training.domain.Training;

/**
 * Il caricamento a mano dei pesi di un training riuscito sul suo repo HuggingFace: il rimedio quando il trainer non lo ha fatto (la copia risulta non trovata o
 * non verificabile). Non e' un passo del risultato: lo chiede l'utente, con un token scelto in quel momento (quello del lancio potrebbe essere scaduto: e'
 * proprio il caso di una copia non verificabile). Non ripete il training ne' spende su Replicate: scarica i pesi che ha gia' prodotto.
 */
public interface ITrainingHfUploads {

    /** Il training e il suo repo si possono caricare a mano: riuscito, con la copia richiesta e non verificata, e senza un caricamento gia' in corso. */
    boolean canUpload(Training training);

    /** Un caricamento di questo training e' in corso adesso (solo in memoria: un riavvio dell'app lo azzera, e il bottone ricompare). */
    boolean isUploading(Long trainingId);

    /**
     * Valida e avvia il caricamento in background; ritorna subito. Il token deve esistere, non essere scaduto e avere il permesso di scrittura. L'esito (copia
     * verificata, oppure un errore negli eventi di sistema) arriva a caricamento finito, con {@code TrainingChangedEvent}.
     *
     * @throws org.dual.hexa.app.training.domain.TrainingException {@code REJECTED} se il training non si puo' caricare o ne e' gia' in corso uno
     */
    void request(Long trainingId, Long secretId);

    /** Il lavoro vero, da un thread in background (lo chiama il listener di {@code HfUploadRequestedEvent}): non lancia mai, ogni esito e' sulla riga o negli eventi. */
    void upload(Long trainingId, Long secretId);
}
