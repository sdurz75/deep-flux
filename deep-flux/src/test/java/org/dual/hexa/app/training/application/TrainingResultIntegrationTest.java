package org.dual.hexa.app.training.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.dual.hexa.app.generation.domain.ModelVersion;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.app.generation.port.in.IModelCatalog;
import org.dual.hexa.app.generation.port.out.IPredictionGateway;
import org.dual.hexa.app.training.domain.HfStatus;
import org.dual.hexa.app.training.domain.LaunchSettings;
import org.dual.hexa.app.training.domain.LoraType;
import org.dual.hexa.app.training.domain.ModelStatus;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.app.training.port.in.ITrainingResults;
import org.dual.hexa.app.training.port.out.IHuggingFaceRepos;
import org.dual.hexa.app.training.port.out.ITrainerGateway;
import org.dual.hexa.app.training.port.out.ITrainingDatasetStore;
import org.dual.hexa.app.training.port.out.ITrainingStore;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Il risultato di un training riuscito sul DB vero e sui servizi VERI dei preset e del catalogo (cio' che gli unit test con i mock non vedono: che il preset creato
 * dal training diventi davvero un modello censito come fine-tune, senza token, e compaia dove compare ogni altro). Fuori restano solo Replicate (lettura della versione
 * del modello, finta) e HuggingFace. NESSUNA rete.
 */
@SpringBootTest
class TrainingResultIntegrationTest {

    private static final String MODEL = "acct/il-mio-gatto-20261005-100000";

    @MockitoBean
    private IPredictionGateway predictions;
    @MockitoBean
    private ITrainerGateway trainer;
    @MockitoBean
    private IHuggingFaceRepos huggingFace;

    @Autowired
    private ITrainingResults results;
    @Autowired
    private ITrainingStore trainingStore;
    @Autowired
    private ITrainingDatasetStore datasetStore;
    @Autowired
    private ILoraPresets presets;
    @Autowired
    private IModelCatalog catalog;
    @Autowired
    private ISecrets secrets;
    @Autowired
    private JdbcTemplate jdbc;

    private Long secretId;

    @BeforeEach
    void stubs() {
        // La versione di un LoRA di Flux ha lora_scale: e' cio' che il catalogo cerca per censirlo come fine-tune.
        when(predictions.latestVersion(MODEL)).thenReturn(Optional.of(new ModelVersion("hash-1", Set.of("prompt", "lora_scale", "num_outputs"))));
        secretId = secrets.create("HUGGINGFACE", "hf-test", "hf_secret", null).id();
        when(huggingFace.repoFiles(anyString(), anyString())).thenReturn(Optional.of(List.of("lora.safetensors")));
    }

    @AfterEach
    void cleanUp() {
        trainingStore.deleteAll();
        datasetStore.deleteAll();
        presets.list().stream().filter(p -> MODEL.equals(p.source())).forEach(p -> presets.delete(p.id()));
        jdbc.update("DELETE FROM replicate_model WHERE owner = ? AND name = ?", "acct", "il-mio-gatto-20261005-100000");
        secrets.delete(secretId);
    }

    private Training succeededTraining() {
        TrainingDataset snapshot = new TrainingDataset("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null, true, null, Instant.now());
        snapshot.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, secretId, null, true), Instant.now());
        snapshot = datasetStore.save(snapshot);
        Training training = new Training(snapshot, "train-1", TrainingStatus.PROCESSING, "v", "acct", "il-mio-gatto-20261005-100000",
                "sandro/il-mio-gatto-20261005-100000", Instant.now().minusSeconds(600));
        training.succeed("ok", 100.0, Instant.now());
        return trainingStore.save(training);
    }

    @Test
    void theTrainedModelBecomesAPresetAndACatalogFineTuneUsableWithoutAToken() {
        Training training = succeededTraining();
        assertThat(catalog.contains(MODEL)).isFalse();

        results.complete(training.getId());

        Training read = trainingStore.findById(training.getId()).orElseThrow();
        assertThat(read.getPresetId()).isNotNull();
        ILoraPresets.LoraView preset = presets.get(read.getPresetId());
        assertThat(preset.name()).isEqualTo("Il mio gatto");
        assertThat(preset.source()).isEqualTo(MODEL);
        assertThat(preset.triggerWords()).isEqualTo("TOKCAT");
        assertThat(preset.scale()).isEqualTo(ILoraPresets.DEFAULT_SCALE);
        assertThat(preset.note()).contains("#" + training.getId());
        assertThat(read.getModelStatus()).isEqualTo(ModelStatus.REGISTERED);
        assertThat(catalog.contains(MODEL)).as("il preset lo ha censito come modello: compare dove compare ogni fine-tune").isTrue();
        assertThat(catalog.versionOf(MODEL)).contains("hash-1");
        assertThat(read.getHfStatus()).isEqualTo(HfStatus.VERIFIED);
        assertThat(read.isResultIncomplete()).isFalse();
    }

    @Test
    void completingTwiceCreatesOnePresetOnly() {
        Training training = succeededTraining();

        results.complete(training.getId());
        results.complete(training.getId());

        assertThat(presets.list().stream().filter(p -> MODEL.equals(p.source()))).hasSize(1);
    }

    @Test
    void aModelWithoutLoraScaleIsNotUsableAndTheUserIsToldAfterTheGracePeriod() {
        when(predictions.latestVersion(MODEL)).thenReturn(Optional.of(new ModelVersion("hash-1", Set.of("prompt"))));
        Training training = succeededTraining();
        // la tolleranza e' di 10 minuti dal completamento: lo si porta indietro nel tempo
        org.springframework.test.util.ReflectionTestUtils.setField(training, "completedAt", Instant.now().minusSeconds(3600));
        trainingStore.save(training);

        results.complete(training.getId());

        Training read = trainingStore.findById(training.getId()).orElseThrow();
        assertThat(read.getPresetId()).as("il preset c'e' comunque: e' solo un'anagrafica").isNotNull();
        assertThat(read.getModelStatus()).isEqualTo(ModelStatus.REJECTED);
        assertThat(catalog.contains(MODEL)).isFalse();
    }

    @Test
    void recoverPendingCompletesAResultLeftHalfDone() {
        Training training = succeededTraining();

        int resumed = results.recoverPending();

        assertThat(resumed).isEqualTo(1);
        assertThat(trainingStore.findById(training.getId()).orElseThrow().isResultIncomplete()).isFalse();
        assertThat(results.recoverPending()).as("niente piu' da riprendere").isZero();
    }
}
