package org.dual.replicate.app.training.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.training.domain.LaunchCheck;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.core.kernel.Paged;

/**
 * I training di un LoRA: lancio da una bozza, avanzamento, annullamento, storico. Ogni training ha il PROPRIO dataset congelato (le immagini e le didascalie
 * com'erano al lancio) e il proprio modello Replicate di destinazione. {@link #start} SPENDE (un training su Replicate e' a pagamento): non si ritenta mai.
 */
public interface ITrainings {

    /** Lo storico, l'ultimo lanciato prima. {@code pageIndex} parte da 0. */
    Paged<Training> page(int pageIndex, int pageSize);

    Optional<Training> find(Long id);

    /** Un training; uno sconosciuto e' un rifiuto, non un errore interno. */
    Training get(Long id);

    /** Il training che ha come dataset congelato questo (vuoto per una bozza): serve a mostrare da dove viene uno snapshot. */
    Optional<Training> findBySnapshot(Long snapshotDatasetId);

    /** Cosa impedisce o sconsiglia il lancio di questa bozza. Nessuna chiamata remota: sola lettura. */
    LaunchCheck check(Long datasetId);

    /**
     * Lancia il training della bozza: congela una copia del dataset, ne fa lo zip, lo carica, crea il modello di destinazione (e il repo HuggingFace se richiesto)
     * e avvia il training. A PAGAMENTO. Qualunque cosa fallisca prima dell'avvio ripulisce lo snapshot; se fallisce il salvataggio dopo l'avvio il training
     * si annulla.
     *
     * @throws org.dual.replicate.app.training.domain.TrainingException {@code REJECTED} se un controllo non e' superato (la bozza non si tocca)
     */
    Training start(Long datasetId);

    /** Fa avanzare un training non terminale interrogando Replicate; uno terminale si restituisce com'e', senza chiamate. */
    Training refresh(Long id);

    /** Interrompe un training in corso. Se nel frattempo era gia' finito, ne riflette l'esito vero. */
    Training cancel(Long id);

    /** Elimina il training col suo dataset congelato e i file; se era in corso lo annulla. NON elimina il modello Replicate, il repo HuggingFace ne' il preset. */
    void delete(Long id);

    /** I training non terminali, per il poller. */
    List<Training> inProgress();

    /** Un training non terminale che ha superato il suo timeout di business. */
    boolean isOverdue(Training training);
}
