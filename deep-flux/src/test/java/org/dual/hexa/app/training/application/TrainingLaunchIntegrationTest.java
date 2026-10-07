package org.dual.hexa.app.training.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.dual.hexa.app.training.domain.HfAccount;
import org.dual.hexa.app.training.domain.LaunchSettings;
import org.dual.hexa.app.training.domain.LoraType;
import org.dual.hexa.app.training.domain.TrainerJob;
import org.dual.hexa.app.training.domain.Training;
import org.dual.hexa.app.training.domain.TrainingDataset;
import org.dual.hexa.app.training.domain.TrainingException;
import org.dual.hexa.app.training.domain.TrainingImage;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.app.training.port.in.ICaptionJobs;
import org.dual.hexa.app.training.port.in.ITrainingCaptions;
import org.dual.hexa.app.training.port.in.ITrainingDatasets;
import org.dual.hexa.app.training.port.in.ITrainingResults;
import org.dual.hexa.app.training.port.in.ITrainings;
import org.dual.hexa.app.training.port.out.IHuggingFaceRepos;
import org.dual.hexa.app.training.port.out.ITrainerGateway;
import org.dual.hexa.app.training.port.out.ITrainingDatasetStore;
import org.dual.hexa.app.training.port.out.ITrainingStore;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.dual.hexa.core.storage.domain.UploadedFile;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.dual.hexa.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Il lancio di un training end-to-end sul DB VERO e sui servizi veri (use case, snapshot, zip, token cifrato con la chiave di test), con SOLO i confini
 * esterni finti: storage, Replicate (trainer), HuggingFace, lavoro di didascalia. Prova cio' che gli unit test con lo store in memoria non vedono: che lo
 * snapshot si rilegga fuori da una transazione (open-in-view e' spento), che il token HuggingFace salvato cifrato arrivi al trainer e a nessuna colonna, che
 * il secondo lancio sia rifiutato dalla riga vera, che eliminare un training elimini il suo snapshot e non la bozza. NESSUNA rete, NESSUN training reale.
 */
@SpringBootTest
class TrainingLaunchIntegrationTest {

    private static final String HF_SECRET = "hf_SUPER_SECRET_value";

    @MockitoBean
    private IImageStorageService storage;
    @MockitoBean
    private ITrainerGateway trainer;
    @MockitoBean
    private IHuggingFaceRepos huggingFace;
    /** Il lavoro di didascalia chiamerebbe il modello di visione vero. */
    @MockitoBean
    private ICaptionJobs captionJobs;
    /** Un annullamento che trova il training riuscito pubblica il completamento: il risultato (preset, modelli) non si crea nel DB condiviso dei test. */
    @MockitoBean
    private ITrainingResults results;

    @Autowired
    private ITrainings trainings;
    @Autowired
    private ITrainingDatasets datasets;
    @Autowired
    private ITrainingCaptions captions;
    @Autowired
    private ITrainingStore trainingStore;
    @Autowired
    private ITrainingDatasetStore datasetStore;
    @Autowired
    private IApiTokens tokens;
    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger names = new AtomicInteger();
    private final AtomicInteger copies = new AtomicInteger();
    private final AtomicReference<Map<String, String>> uploadedZip = new AtomicReference<>();
    private Long tokenId;

    @BeforeEach
    void stubs() {
        when(storage.storeUpload(any(UploadedFile.class))).thenAnswer(i -> "file-" + names.incrementAndGet() + ".jpg");
        when(storage.copy(anyString())).thenAnswer(i -> "copy-" + copies.incrementAndGet() + ".jpg");
        when(storage.read(anyString())).thenAnswer(i -> new SourceImage(("bytes-of-" + i.getArgument(0)).getBytes(), "image/jpeg"));

        when(huggingFace.whoami(HF_SECRET)).thenReturn(new HfAccount("sandro", "write"));
        when(trainer.trainerVersion()).thenReturn("v-trainer");
        when(trainer.uploadFile(anyString(), any())).thenAnswer(i -> {
            uploadedZip.set(unzip(((org.dual.hexa.app.training.domain.DatasetArchive) i.getArgument(1)).open()));
            return "https://api.replicate.com/v1/files/abc";
        });
        when(trainer.ensureDestination(anyString())).thenAnswer(i -> "acct/" + i.getArgument(0));
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenReturn(new TrainerJob("train-1", TrainingStatus.PENDING, null, null, null));

        tokenId = tokens.create("HUGGINGFACE", "hf-test", HF_SECRET, null).id();
    }

    @AfterEach
    void cleanUp() {
        trainingStore.deleteAll();
        datasetStore.deleteAll();
        if (tokenId != null) {
            tokens.delete(tokenId);
        }
    }

    private Long readyDraft(boolean huggingFaceCopy) {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();
        datasets.addImages(id, List.of(upload("a.jpg"), upload("b.jpg"), upload("c.jpg"), upload("d.jpg"), upload("e.jpg")));
        for (TrainingImage image : datasets.get(id).getImages()) {
            captions.saveCaption(id, image.getId(), "TOKCAT, foto di " + image.getOriginalName());
        }
        datasets.saveLaunchSettings(id, new LaunchSettings("Il mio gatto", 1200, 42L, huggingFaceCopy, huggingFaceCopy ? tokenId : null, null, true));
        return id;
    }

    @Test
    void startLaunchesFromARealDraftAndKeepsTheSnapshotReadableOutsideATransaction() {
        Long draftId = readyDraft(true);

        Training started = trainings.start(draftId);

        Training read = trainings.get(started.getId());
        assertThat(read.getStatus()).isEqualTo(TrainingStatus.PENDING);
        assertThat(read.getSourceDatasetId()).isEqualTo(draftId);
        assertThat(read.getImageCount()).isEqualTo(5);
        assertThat(read.getHfRepoId()).startsWith("sandro/il-mio-gatto-");

        TrainingDataset snapshot = datasets.get(read.getSnapshotDatasetId());
        assertThat(snapshot.isFrozen()).isTrue();
        assertThat(snapshot.getImages()).hasSize(5).extracting(TrainingImage::getFilename).allMatch(f -> f.startsWith("copy-"));
        assertThat(snapshot.getImages()).extracting(TrainingImage::getCaption).allMatch(c -> c.startsWith("TOKCAT, foto di"));
        assertThat(trainings.findBySnapshot(snapshot.getId())).isPresent();
        assertThat(datasets.page(0, 10).content()).as("lo snapshot non e' una bozza").extracting(TrainingDataset::getId).containsExactly(draftId);
        verify(storage, times(5)).copy(anyString());
    }

    @Test
    void theZipUploadedToTheTrainerHasTheSnapshotImagesAndTheirCaptions() {
        trainings.start(readyDraft(false));

        Map<String, String> zip = uploadedZip.get();
        assertThat(zip).containsOnlyKeys("img_001.jpg", "img_001.txt", "img_002.jpg", "img_002.txt", "img_003.jpg", "img_003.txt", "img_004.jpg", "img_004.txt",
                "img_005.jpg", "img_005.txt");
        assertThat(zip.get("img_001.jpg")).startsWith("bytes-of-copy-");
        assertThat(zip.get("img_001.txt")).startsWith("TOKCAT, foto di");
    }

    @Test
    void theHuggingFaceTokenSavedEncryptedReachesTheTrainerAndNoColumn() {
        Long draftId = readyDraft(true);

        Training started = trainings.start(draftId);

        verify(trainer).createTraining(anyString(), anyString(), org.mockito.ArgumentMatchers.argThat(input ->
                HF_SECRET.equals(input.get("hf_token")) && input.get("hf_repo_id").toString().startsWith("sandro/")));
        verify(huggingFace).createModelRepo(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM training WHERE id = ?", started.getId());
        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(HF_SECRET));
        Map<String, Object> draftRow = jdbc.queryForMap("SELECT * FROM training_dataset WHERE id = ?", draftId);
        assertThat(draftRow.values()).as("la bozza ricorda l'ID del token, mai il token").noneMatch(v -> v != null && v.toString().contains(HF_SECRET));
        assertThat(draftRow.get("hf_token_id")).isEqualTo(tokenId);
    }

    @Test
    void aSecondLaunchOfTheSameDraftIsRejectedByTheRealRowAndSpendsNothing() {
        Long draftId = readyDraft(false);
        trainings.start(draftId);

        assertThatThrownBy(() -> trainings.start(draftId)).isInstanceOf(TrainingException.class);

        verify(trainer, times(1)).createTraining(anyString(), anyString(), anyMap());
        assertThat(trainings.check(draftId).isLaunchable()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_dataset WHERE frozen", Integer.class)).as("nessuno snapshot orfano").isEqualTo(1);
    }

    @Test
    void aFailedCreationLeavesNoSnapshotNoRowAndTheDraftIntact() {
        Long draftId = readyDraft(false);
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("402"));
        int draftImages = datasets.get(draftId).getImages().size();

        assertThatThrownBy(() -> trainings.start(draftId)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM training", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM training_dataset WHERE frozen", Integer.class)).isZero();
        assertThat(datasets.get(draftId).getImages()).hasSize(draftImages);
        verify(storage, times(5)).delete(anyString());
    }

    @Test
    void refreshAndCancelWorkOnTheRealRowAndCancelKeepsASucceededOutcome() {
        Training started = trainings.start(readyDraft(false));
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.PROCESSING, null, "step 10", null));

        assertThat(trainings.refresh(started.getId()).getStatus()).isEqualTo(TrainingStatus.PROCESSING);
        assertThat(trainings.get(started.getId()).getLogs()).isEqualTo("step 10");
        assertThat(trainings.inProgress()).extracting(Training::getId).containsExactly(started.getId());

        when(trainer.cancelTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "ok", 77.0));
        assertThat(trainings.cancel(started.getId()).getStatus()).isEqualTo(TrainingStatus.SUCCEEDED);
        assertThat(trainings.get(started.getId()).getPredictTimeSeconds()).isEqualTo(77.0);
        assertThat(trainings.inProgress()).isEmpty();
    }

    @Test
    void deletingATrainingRemovesItsSnapshotAndFilesButNeverTheDraft() {
        Long draftId = readyDraft(false);
        Training started = trainings.start(draftId);
        Long snapshotId = started.getSnapshotDatasetId();
        List<String> snapshotFiles = datasets.get(snapshotId).getImages().stream().map(TrainingImage::getFilename).toList();
        List<String> draftFiles = datasets.get(draftId).getImages().stream().map(TrainingImage::getFilename).toList();
        when(trainer.cancelTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.CANCELED, null, null, null));
        reset(storage);

        trainings.delete(started.getId());

        assertThat(trainingStore.findById(started.getId())).isEmpty();
        assertThat(datasetStore.findById(snapshotId)).isEmpty();
        for (String file : snapshotFiles) {
            verify(storage).delete(file);
        }
        for (String file : draftFiles) {
            verify(storage, never()).delete(file);
        }
        assertThat(datasets.get(draftId).getImages()).as("la bozza resta com'era").hasSize(5);
        verify(trainer).cancelTraining("train-1");
    }

    private static UploadedFile upload(String name) {
        return UploadedFile.of(name, new byte[] {1, 2, 3});
    }

    private static Map<String, String> unzip(InputStream in) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(in.readAllBytes()))) {
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                entries.put(entry.getName(), new String(zis.readAllBytes()));
            }
        }
        return entries;
    }
}
