package org.dual.hexa.app.training.adapter.out.persistence;

import java.time.Instant;
import java.util.List;

import org.dual.hexa.app.training.domain.HfStatus;
import org.dual.hexa.app.training.domain.LaunchSettings;
import org.dual.hexa.app.training.domain.LoraType;
import org.dual.hexa.app.training.domain.ModelStatus;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.app.training.port.out.ITrainingDatasetStore;
import org.dual.hexa.app.training.port.out.ITrainingStore;
import org.dual.hexa.core.kernel.Paged;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lo storico dei training e le impostazioni di lancio sul DB VERO (schema Flyway reale, nessuna rete). */
@SpringBootTest
class JpaTrainingStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Autowired
    private ITrainingStore trainings;
    @Autowired
    private ITrainingDatasetStore datasets;

    @AfterEach
    void cleanUp() {
        trainings.deleteAll();
        datasets.deleteAll();
    }

    private TrainingDataset snapshot(String name) {
        TrainingDataset snapshot = new TrainingDataset(name, "TOK", LoraType.SUBJECT, null, true, null, NOW);
        snapshot.addImage("a-" + name + ".jpg", "a.jpg", NOW).writeCaption("TOK, " + name);
        return datasets.save(snapshot);
    }

    private Training training(TrainingDataset snapshot, String externalId, TrainingStatus status, Instant at) {
        return trainings.save(new Training(snapshot, externalId, status, "v1", "acct", "model-" + externalId, "sandro/model-" + externalId, at));
    }

    @Test
    void aTrainingRoundTripsWithItsOwnSnapshotAndNoSecret() {
        TrainingDataset snapshot = snapshot("gatto");
        Training saved = training(snapshot, "t-1", TrainingStatus.PENDING, NOW);

        Training read = trainings.findById(saved.getId()).orElseThrow();

        assertThat(read.getSnapshotDatasetId()).isEqualTo(snapshot.getId());
        assertThat(read.getName()).isEqualTo("gatto");
        assertThat(read.getTriggerWord()).isEqualTo("TOK");
        assertThat(read.getLoraType()).isEqualTo(LoraType.SUBJECT);
        assertThat(read.getImageCount()).isEqualTo(1);
        assertThat(read.getExternalId()).isEqualTo("t-1");
        assertThat(read.destinationModel()).isEqualTo("acct/model-t-1");
        assertThat(read.getHfRepoId()).isEqualTo("sandro/model-t-1");
        assertThat(read.getCreatedAt()).isEqualTo(NOW);
        assertThat(read.getTrainingSteps()).isEqualTo(1000);
        assertThat(trainings.findBySnapshotDatasetId(snapshot.getId())).get().extracting(Training::getId).isEqualTo(saved.getId());
    }

    @Test
    void progressAndOutcomeArePersisted() {
        Training saved = training(snapshot("gatto"), "t-1", TrainingStatus.PENDING, NOW);
        Training loaded = trainings.findById(saved.getId()).orElseThrow();

        loaded.advance(TrainingStatus.PROCESSING, "step 10");
        trainings.save(loaded);
        assertThat(trainings.findById(saved.getId()).orElseThrow().getLogs()).isEqualTo("step 10");

        loaded.succeed("done", 512.5, NOW.plusSeconds(600));
        trainings.save(loaded);
        Training done = trainings.findById(saved.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(TrainingStatus.SUCCEEDED);
        assertThat(done.getPredictTimeSeconds()).isEqualTo(512.5);
        assertThat(done.getCompletedAt()).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void longLogsAreKeptToTheirTailAndPersistedWholeBecauseTheColumnIsText() {
        Training saved = training(snapshot("gatto"), "t-1", TrainingStatus.PENDING, NOW);
        Training loaded = trainings.findById(saved.getId()).orElseThrow();
        String logs = ("riga di log lunga lunga lunga\n").repeat(2000) + "ULTIMA";

        loaded.advance(TrainingStatus.PROCESSING, logs);
        trainings.save(loaded);

        String stored = trainings.findById(saved.getId()).orElseThrow().getLogs();
        assertThat(stored).hasSizeLessThanOrEqualTo(Training.MAX_LOG_CHARS).endsWith("ULTIMA");
    }

    @Test
    void thePageIsTheNewestFirst() {
        training(snapshot("uno"), "t-1", TrainingStatus.SUCCEEDED, NOW);
        training(snapshot("due"), "t-2", TrainingStatus.FAILED, NOW.plusSeconds(60));
        training(snapshot("tre"), "t-3", TrainingStatus.PENDING, NOW.plusSeconds(120));

        Paged<Training> page = trainings.findPage(0, 2);
        Paged<Training> next = trainings.findPage(1, 2);

        assertThat(page.content()).extracting(Training::getName).containsExactly("tre", "due");
        assertThat(next.content()).extracting(Training::getName).containsExactly("uno");
        assertThat(page.totalElements()).isEqualTo(3);
    }

    @Test
    void findByStatusInReturnsOnlyTheRequestedStatuses() {
        Training pending = training(snapshot("a"), "t-1", TrainingStatus.PENDING, NOW);
        Training processing = training(snapshot("b"), "t-2", TrainingStatus.PROCESSING, NOW);
        training(snapshot("c"), "t-3", TrainingStatus.SUCCEEDED, NOW);

        List<Training> running = trainings.findByStatusIn(List.of(TrainingStatus.PENDING, TrainingStatus.PROCESSING));

        assertThat(running).extracting(Training::getId).containsExactlyInAnyOrder(pending.getId(), processing.getId());
    }

    @Test
    void findResultsToCompleteFindsOnlyRecentSucceededTrainingsWithSomethingLeftToDo() {
        Instant now = NOW.plusSeconds(3600);
        Training noPreset = succeededAt("a", now);
        Training modelPending = succeededAt("b", now);
        modelPending.setPresetId(1L);
        modelPending.setHfStatus(HfStatus.VERIFIED);
        Training hfPending = succeededAt("c", now);
        hfPending.setPresetId(2L);
        hfPending.setModelStatus(ModelStatus.REGISTERED);
        Training complete = succeededAt("d", now);
        complete.setPresetId(3L);
        complete.setModelStatus(ModelStatus.REGISTERED);
        complete.setHfStatus(HfStatus.VERIFIED);
        Training rejected = succeededAt("e", now);
        rejected.setPresetId(4L);
        rejected.setModelStatus(ModelStatus.REJECTED);
        rejected.setHfStatus(HfStatus.NOT_FOUND);
        Training tooOld = succeededAt("f", now.minusSeconds(8 * 3600));
        Training running = training(snapshot("g"), "t-g", TrainingStatus.PROCESSING, now);
        List.of(noPreset, modelPending, hfPending, complete, rejected, tooOld).forEach(trainings::save);

        List<Training> found = trainings.findResultsToComplete(now.minusSeconds(6 * 3600));

        assertThat(found).extracting(Training::getId).containsExactlyInAnyOrder(noPreset.getId(), modelPending.getId(), hfPending.getId());
        assertThat(found).doesNotContain(running);
    }

    @Test
    void theResultStateRoundTrips() {
        Training saved = succeededAt("a", NOW);
        saved.setPresetId(77L);
        saved.setModelStatus(ModelStatus.REJECTED);
        saved.setHfStatus(HfStatus.UNVERIFIED);
        trainings.save(saved);

        Training read = trainings.findById(saved.getId()).orElseThrow();

        assertThat(read.getPresetId()).isEqualTo(77L);
        assertThat(read.getModelStatus()).isEqualTo(ModelStatus.REJECTED);
        assertThat(read.getHfStatus()).isEqualTo(HfStatus.UNVERIFIED);
        assertThat(trainings.findById(training(snapshot("fresh"), "t-fresh", TrainingStatus.PENDING, NOW).getId()).orElseThrow().getModelStatus())
                .as("un training nuovo ha il modello da preparare").isEqualTo(ModelStatus.PENDING);
    }

    /** Un training riuscito alla data data, salvato (con il suo snapshot), con il risultato ancora da fare. */
    private Training succeededAt(String name, Instant completedAt) {
        Training training = training(snapshot(name), "t-" + name, TrainingStatus.PROCESSING, completedAt.minusSeconds(60));
        training.succeed("ok", 1.0, completedAt);
        return trainings.save(training);
    }

    @Test
    void aTrainingKeepsItsSnapshotAliveByForeignKey() {
        TrainingDataset snapshot = snapshot("gatto");
        training(snapshot, "t-1", TrainingStatus.SUCCEEDED, NOW);

        assertThatThrownBy(() -> datasets.delete(datasets.findById(snapshot.getId()).orElseThrow()))
                .as("lo snapshot di un training non si elimina da sotto: prima il training")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheTrainingLeavesNoOtherRowAndDeletingTheDraftNeverTouchesIt() {
        TrainingDataset draft = datasets.save(new TrainingDataset("bozza", "TOK", LoraType.SUBJECT, null, NOW));
        TrainingDataset snapshot = datasets.save(new TrainingDataset("bozza", "TOK", LoraType.SUBJECT, null, true, draft.getId(), NOW));
        Training saved = training(snapshot, "t-1", TrainingStatus.SUCCEEDED, NOW);

        datasets.delete(datasets.findById(draft.getId()).orElseThrow()); // niente FK sulla provenienza: la bozza puo' sparire
        assertThat(trainings.findById(saved.getId())).isPresent();
        assertThat(datasets.findById(snapshot.getId())).isPresent();

        trainings.delete(trainings.findById(saved.getId()).orElseThrow());
        datasets.delete(datasets.findById(snapshot.getId()).orElseThrow());
        assertThat(trainings.findById(saved.getId())).isEmpty();
        assertThat(datasets.findById(snapshot.getId())).isEmpty();
    }

    @Test
    void launchSettingsPersistWithTheDraftAndDefaultToTheHuggingFaceCopyOn() {
        TrainingDataset fresh = datasets.save(new TrainingDataset("bozza", "TOK", LoraType.SUBJECT, null, NOW));
        assertThat(datasets.findById(fresh.getId()).orElseThrow().launchSettings()).isEqualTo(LaunchSettings.defaults());

        TrainingDataset loaded = datasets.findById(fresh.getId()).orElseThrow();
        loaded.applyLaunchSettings(new LaunchSettings("gatto", 1500, 42L, false, 7L, "mio-repo", false), NOW);
        datasets.save(loaded);

        assertThat(datasets.findById(fresh.getId()).orElseThrow().launchSettings())
                .isEqualTo(new LaunchSettings("gatto", 1500, 42L, false, 7L, "mio-repo", false));
    }
}
