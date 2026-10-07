package org.dual.hexa.app.training.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.core.kernel.Paged;

/** Persistenza dei training (lo storico). I loro dataset congelati sono dello store dei dataset. */
public interface ITrainingStore {

    Training save(Training training);

    Optional<Training> findById(Long id);

    /** Lo storico, l'ultimo lanciato prima. {@code pageIndex} parte da 0. */
    Paged<Training> findPage(int pageIndex, int pageSize);

    List<Training> findByStatusIn(Collection<TrainingStatus> statuses);

    /** I training riusciti da {@code completedSince} in poi il cui risultato non e' completo (preset, modello utilizzabile, copia su HuggingFace). */
    List<Training> findResultsToComplete(Instant completedSince);

    /** Il training che usa questo dataset congelato, se c'e' (per riconoscere uno snapshot dalla pagina del dataset). */
    Optional<Training> findBySnapshotDatasetId(Long snapshotDatasetId);

    void delete(Training training);

    /** Svuota la tabella (test e reset). */
    void deleteAll();
}
