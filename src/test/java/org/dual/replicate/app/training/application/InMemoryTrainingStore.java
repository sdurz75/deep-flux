package org.dual.replicate.app.training.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.port.out.ITrainingStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.test.util.ReflectionTestUtils;

/** Store dei training in memoria: assegna gli id, ritorna le stesse istanze (i test guardano la riga dopo che il servizio l'ha mutata) e puo' essere fatto fallire. */
final class InMemoryTrainingStore implements ITrainingStore {

    final Map<Long, Training> rows = new LinkedHashMap<>();
    RuntimeException failOnSave;
    int saves;
    private long nextId = 1;

    @Override
    public Training save(Training training) {
        if (failOnSave != null) {
            throw failOnSave;
        }
        saves++;
        if (training.getId() == null) {
            ReflectionTestUtils.setField(training, "id", nextId++);
        }
        rows.put(training.getId(), training);
        return training;
    }

    @Override
    public Optional<Training> findById(Long id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Paged<Training> findPage(int pageIndex, int pageSize) {
        List<Training> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparing(Training::getCreatedAt).thenComparing(Training::getId).reversed());
        int from = Math.min(pageIndex * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new Paged<>(all.subList(from, to), pageIndex, pageSize, all.size());
    }

    @Override
    public List<Training> findByStatusIn(Collection<TrainingStatus> statuses) {
        return rows.values().stream().filter(t -> statuses.contains(t.getStatus())).toList();
    }

    @Override
    public Optional<Training> findBySnapshotDatasetId(Long snapshotDatasetId) {
        return rows.values().stream().filter(t -> t.getSnapshotDatasetId().equals(snapshotDatasetId)).findFirst();
    }

    @Override
    public void delete(Training training) {
        rows.remove(training.getId());
    }

    @Override
    public void deleteAll() {
        rows.clear();
    }
}
