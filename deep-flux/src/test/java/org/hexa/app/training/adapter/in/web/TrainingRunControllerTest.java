package org.hexa.app.training.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hexa.app.generation.port.in.ILoraPresets;
import org.hexa.app.generation.port.out.IPredictionGateway;
import org.hexa.app.training.domain.HfAccount;
import org.hexa.app.training.domain.HfStatus;
import org.hexa.app.training.domain.LaunchSettings;
import org.hexa.app.training.domain.LoraType;
import org.hexa.app.training.domain.ModelStatus;
import org.hexa.app.training.domain.TrainerJob;
import org.hexa.app.training.domain.Training;
import org.hexa.app.training.domain.TrainingDataset;
import org.hexa.app.training.domain.TrainingImage;
import org.hexa.app.training.domain.TrainingStatus;
import org.hexa.app.training.domain.WeightsFile;
import org.hexa.app.training.port.in.ICaptionJobs;
import org.hexa.app.training.port.in.ITrainingCaptions;
import org.hexa.app.training.port.in.ITrainingDatasets;
import org.hexa.app.training.port.in.ITrainingResults;
import org.hexa.app.training.port.out.IHuggingFaceRepos;
import org.hexa.app.training.port.out.ITrainerGateway;
import org.hexa.app.training.port.out.ITrainingDatasetStore;
import org.hexa.app.training.port.out.ITrainingStore;
import org.hexa.core.storage.domain.SourceImage;
import org.hexa.core.storage.domain.UploadedFile;
import org.hexa.core.storage.port.in.IImageStorageService;
import org.hexa.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il training sul web: hub a schede (bozze / storico), pannello "Avvia" di una bozza, dettaglio con avanzamento, annullo, eliminazione. Replicate, HuggingFace e lo
 * storage sono MOCKATI: nessuna chiamata di rete, nessun training, nessun file. Quel che si controlla sulla pagina e' il markup che il server decide (che polla e
 * che no, quali azioni compaiono, cosa dice il pannello), non l'aspetto: il JavaScript del browser non e' provato qui.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrainingRunControllerTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @MockitoBean
    private IImageStorageService storage;
    @MockitoBean
    private ITrainerGateway trainer;
    @MockitoBean
    private IHuggingFaceRepos huggingFace;
    @MockitoBean
    private ICaptionJobs captionJobs;
    /** Creare un preset prova a censire il modello leggendone la versione da Replicate: qui e' finto (nessuna rete). */
    @MockitoBean
    private IPredictionGateway predictions;
    /**
     * Un training portato a "riuscito" pubblica il completamento e il listener asincrono creerebbe davvero preset e modelli nel DB condiviso dei test: qui il
     * risultato e' finto (la sua logica e' provata in TrainingResultServiceTest e TrainingResultIntegrationTest).
     */
    @MockitoBean
    private ITrainingResults results;
    @Autowired
    private ILoraPresets presets;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ITrainingDatasets datasets;
    @Autowired
    private ITrainingCaptions captions;
    @Autowired
    private ITrainingDatasetStore datasetStore;
    @Autowired
    private ITrainingStore trainingStore;
    @Autowired
    private IApiTokens tokens;

    private final AtomicInteger names = new AtomicInteger();

    @BeforeEach
    void stubs() {
        when(storage.storeUpload(any(UploadedFile.class))).thenAnswer(i -> "file-" + names.incrementAndGet() + ".png");
        when(storage.copy(anyString())).thenAnswer(i -> "copy-" + names.incrementAndGet() + ".png");
        when(storage.read(anyString())).thenReturn(new SourceImage(PNG, "image/png"));
        when(trainer.trainerVersion()).thenReturn("v-trainer");
        when(trainer.uploadFile(anyString(), any())).thenReturn("https://api.replicate.com/v1/files/abc");
        when(trainer.ensureDestination(anyString())).thenAnswer(i -> "acct/" + i.getArgument(0));
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenReturn(new TrainerJob("train-1", TrainingStatus.PENDING, null, null, null));
    }

    @AfterEach
    void cleanUp() {
        trainingStore.deleteAll();
        datasetStore.deleteAll();
        tokens.list().stream().filter(t -> "hf-upload-test".equals(t.name())).forEach(t -> tokens.delete(t.id()));
        presets.list().stream().filter(p -> "acct/il-mio-gatto-20261005-100000".equals(p.source())).forEach(p -> presets.delete(p.id()));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** Una bozza pronta al lancio: 5 immagini con didascalia, copia su HuggingFace spenta (nessun token). */
    private Long readyDraft() {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();
        datasets.addImages(id, List.of(UploadedFile.of("a.png", PNG), UploadedFile.of("b.png", PNG), UploadedFile.of("c.png", PNG),
                UploadedFile.of("d.png", PNG), UploadedFile.of("e.png", PNG)));
        for (TrainingImage image : datasets.get(id).getImages()) {
            captions.saveCaption(id, image.getId(), "TOKCAT, una foto");
        }
        datasets.saveLaunchSettings(id, new LaunchSettings("Il mio gatto", 1000, null, false, null, null, true));
        return id;
    }

    /** Un training gia' salvato nello stato dato, col suo snapshot congelato. */
    private Training training(TrainingStatus status) {
        TrainingDataset snapshot = new TrainingDataset("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null, true, null, NOW);
        snapshot.addImage("snap-1.png", "gatto.png", NOW).writeCaption("TOKCAT, una foto");
        snapshot = datasetStore.save(snapshot);
        // Creato "adesso": il servizio usa l'orologio vero e un training piu' vecchio del timeout di business verrebbe chiuso dal poll.
        Training training = new Training(snapshot, "train-1", status, "v-trainer", "acct", "il-mio-gatto-20261005-100000", "sandro/il-mio-gatto", Instant.now());
        return trainingStore.save(training);
    }

    /** Un training riuscito col risultato gia' completo (un preset c'e', il modello e' censito, la copia su HuggingFace verificata). */
    private Training succeededWithResult(Long presetId) {
        Training training = training(TrainingStatus.SUCCEEDED);
        training.setPresetId(presetId);
        training.setModelStatus(ModelStatus.REGISTERED);
        training.setHfStatus(HfStatus.VERIFIED);
        return trainingStore.save(training);
    }

    // --- hub a schede -------------------------------------------------------------------------------------------

    @Test
    void theDatasetsTabIsTheDefaultAndDoesNotShowTheHistory() throws Exception {
        training(TrainingStatus.SUCCEEDED);

        String page = body(mockMvc.perform(get("/trainings")).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Nuovo dataset").contains("Nessun dataset ancora").contains("tab=history").doesNotContain("Il mio gatto #")
                .doesNotContain("training-update");
    }

    @Test
    void theHistoryTabListsTheTrainingsWithTheirStatusAndLinksToThem() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);
        Training running = training(TrainingStatus.PROCESSING);

        String page = body(mockMvc.perform(get("/trainings").param("tab", "history")).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Storico dei training").contains("Il mio gatto").contains("TOKCAT").contains("1000 passi")
                .contains("Completato").contains("In corso")
                .contains("href=\"/trainings/" + done.getId() + "\"").contains("href=\"/trainings/" + running.getId() + "\"")
                .doesNotContain("Nuovo dataset").doesNotContain("Nessun training ancora")
                .contains("training-update"); // SSE solo qui: lo storico si aggiorna da solo
    }

    @Test
    void anEmptyHistoryTellsWhereToStart() throws Exception {
        String page = body(mockMvc.perform(get("/trainings").param("tab", "history")).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Nessun training ancora");
    }

    @Test
    void theHistoryPaginationAnswersWithTheBareContentForHtmx() throws Exception {
        training(TrainingStatus.FAILED);

        String fragment = body(mockMvc.perform(get("/trainings").param("tab", "history").header("HX-Request", "true")).andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("Il mio gatto").contains("Fallito").doesNotContain("<html").doesNotContain("Storico dei training");
    }

    // --- dettaglio e avanzamento --------------------------------------------------------------------------------

    @Test
    void aRunningTrainingPagePollsItsStatusEveryFiveSeconds() throws Exception {
        Training running = training(TrainingStatus.PROCESSING);

        String page = body(mockMvc.perform(get("/trainings/" + running.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Training #" + running.getId()).contains("id=\"training-status\"")
                .contains("hx-get=\"/trainings/" + running.getId() + "/status\"").contains("hx-trigger=\"every 5s\"")
                .contains("/trainings/" + running.getId() + "/cancel").contains("Annulla il training")
                .contains("/trainings/" + running.getId() + "/delete")
                .contains("/trainings/datasets/" + running.getSnapshotDatasetId() + "/duplicate") // "Riprendi da questo" = clone dello snapshot
                .contains("il-mio-gatto-20261005-100000").contains("sandro/il-mio-gatto")
                .containsPattern("aria-current=\"page\"[^>]*>Training #" + running.getId() + "<");
        // il training non e' stato interrogato per il solo render della pagina: lo fa il polling del blocco stato
        verifyNoInteractions(trainer);
    }

    @Test
    void aFinishedTrainingPageHasNoPollingAndNoCancel() throws Exception {
        Training done = succeededWithResult(1L);

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Completato").doesNotContain("every 5s").doesNotContain("Annulla il training")
                .contains("href=\"/trainings/datasets/" + done.getSnapshotDatasetId() + "\"");
    }

    @Test
    void aSucceededTrainingWhoseResultIsIncompleteKeepsPollingAndSaysThePresetIsBeingCreated() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Risultato").contains("Preset in /loras: in creazione").contains("Il modello si sta preparando")
                .contains("hx-trigger=\"every 5s\"").doesNotContain("Annulla il training");
    }

    @Test
    void aSucceededTrainingShowsItsPresetAndTheUsableModel() throws Exception {
        ILoraPresets.LoraView preset = presets.create("Il mio gatto", "acct/il-mio-gatto-20261005-100000", 1.0, "TOKCAT", "nota");
        Training done = succeededWithResult(preset.id());

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Risultato").contains("Preset &quot;Il mio gatto&quot; in /loras").contains("href=\"/loras\"")
                .contains("utilizzabile").contains("Presente su HuggingFace").doesNotContain("every 5s");
    }

    @Test
    void aDeletedPresetIsSaidSoInsteadOfBreakingThePage() throws Exception {
        Training done = succeededWithResult(987654L);

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Il preset creato da questo training e&#39; stato eliminato").doesNotContain("every 5s");
    }

    @Test
    void anIncompleteResultStopsPollingOnceTheRetryWindowIsOver() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);
        // oltre la finestra di ripresa (6 h) nulla completera' piu' il risultato: la pagina lasciata aperta non deve interrogare per sempre
        org.springframework.test.util.ReflectionTestUtils.setField(done, "completedAt", Instant.now().minusSeconds(7 * 3600));
        trainingStore.save(done);

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Preset in /loras: in creazione").doesNotContain("every 5s");
    }

    @Test
    void aRejectedModelPointsToTheSystemEventsAndStopsPolling() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);
        done.setPresetId(1L);
        done.setModelStatus(ModelStatus.REJECTED);
        done.setHfStatus(HfStatus.NOT_FOUND);
        trainingStore.save(done);

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("non e&#39; utilizzabile nell&#39;app").contains("href=\"/system/events\"").contains("Pesi non trovati su HuggingFace")
                .doesNotContain("every 5s");
    }

    @Test
    void aFailedTrainingShowsItsErrorAndLogs() throws Exception {
        Training failed = training(TrainingStatus.PROCESSING);
        failed.fail(TrainingStatus.FAILED, "CUDA out of memory", "step 10\nstep 20", 12.0, NOW.plusSeconds(60));
        trainingStore.save(failed);

        String page = body(mockMvc.perform(get("/trainings/" + failed.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Fallito").contains("CUDA out of memory").contains("step 20").contains("12 s");
    }

    @Test
    void anUnknownTrainingRedirectsToTheHistory() throws Exception {
        mockMvc.perform(get("/trainings/999999")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/trainings?tab=history"));
    }

    @Test
    void theStatusPollAsksReplicateAndStopsPollingOnceTheTrainingIsDone() throws Exception {
        Training running = training(TrainingStatus.PROCESSING);
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.PROCESSING, null, "step 30", null));

        String live = body(mockMvc.perform(get("/trainings/" + running.getId() + "/status")).andExpect(status().isOk()).andReturn());
        assertThat(live).contains("step 30").contains("every 5s").doesNotContain("<html");

        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "finito", 600.0));
        String done = body(mockMvc.perform(get("/trainings/" + running.getId() + "/status")).andExpect(status().isOk()).andReturn());

        assertThat(done).contains("Completato").contains("600 s").contains("finito").doesNotContain("Annulla il training")
                .as("il risultato (preset, modello) nasce in background: il blocco continua a controllare finche' e' incompleto").contains("every 5s");
        verify(results, timeout(5000)).complete(running.getId()); // il completamento parte da solo: l'evento e' ascoltato in background
        assertThat(trainingStore.findById(running.getId()).orElseThrow().getStatus()).isEqualTo(TrainingStatus.SUCCEEDED);
    }

    @Test
    void theStatusPollOfADeletedTrainingEndsQuietlyInsteadOfAnError() throws Exception {
        String empty = body(mockMvc.perform(get("/trainings/999999/status")).andExpect(status().isOk()).andReturn());

        assertThat(empty).contains("id=\"training-status\"").doesNotContain("hx-get");
        verifyNoInteractions(trainer);
    }

    @Test
    void cancelAnswersWithTheStatusBlockOfTheRealOutcome() throws Exception {
        Training running = training(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.CANCELED, null, null, null));

        String block = body(mockMvc.perform(post("/trainings/" + running.getId() + "/cancel").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());

        assertThat(block).contains("Annullato").doesNotContain("every 5s").doesNotContain("<html");
        assertThat(trainingStore.findById(running.getId()).orElseThrow().getStatus()).isEqualTo(TrainingStatus.CANCELED);
    }

    @Test
    void cancellingAFinishedTrainingIsAnExpectedRefusal() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);

        mockMvc.perform(post("/trainings/" + done.getId() + "/cancel").header("HX-Request", "true")).andExpect(status().isUnprocessableEntity());

        verify(trainer, never()).cancelTraining(anyString());
    }

    @Test
    void deleteRedirectsToTheHistoryAndRemovesTheTrainingWithItsSnapshot() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);
        Long snapshotId = done.getSnapshotDatasetId();

        mockMvc.perform(post("/trainings/" + done.getId() + "/delete").header("HX-Request", "true")).andExpect(status().isOk())
                .andExpect(header().string("HX-Redirect", "/trainings?tab=history"));

        assertThat(trainingStore.findById(done.getId())).isEmpty();
        assertThat(datasetStore.findById(snapshotId)).isEmpty();
    }

    // --- pannello "Avvia" ---------------------------------------------------------------------------------------

    @Test
    void theEditorOfADraftShowsTheLaunchSettingsWithTheHuggingFaceCopyOnAndPrivateByDefault() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        String page = body(mockMvc.perform(get("/trainings/datasets/" + id)).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Avvia il training").contains("A PAGAMENTO")
                .contains("action=\"/trainings/datasets/" + id + "/launch-settings\"").contains("name=\"trainingSteps\"").contains("value=\"1000\"")
                .containsPattern("name=\"hfPublish\"[^>]*checked").containsPattern("name=\"hfPrivate\"[^>]*checked")
                .contains("name=\"hfTokenId\"").contains("name=\"hfRepoName\"").contains("name=\"modelName\"")
                .contains("id=\"training-launch-check\"").contains("/trainings/datasets/" + id + "/launch-check")
                .contains("Servono almeno 4 immagini").doesNotContain("/start");
    }

    @Test
    void aReadyDraftOffersTheStartWithAPaidActionConfirmation() throws Exception {
        Long id = readyDraft();

        String page = body(mockMvc.perform(get("/trainings/datasets/" + id)).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("hx-post=\"/trainings/datasets/" + id + "/start\"").contains("hx-confirm=").contains("pagamento")
                .contains("data-busy-text=").contains("Avvia il training").doesNotContain("Prima di avviare")
                .as("l'upload dello zip su Replicate puo' superare i 180 s globali di htmx: il form di avvio ha un timeout proprio, piu' lungo")
                .contains("hx-request='{\"timeout\": 900000}'");
    }

    @Test
    void theLaunchCheckFragmentShowsBlockersAndWarningsAndNoStartWhenBlocked() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        String fragment = body(mockMvc.perform(get("/trainings/datasets/" + id + "/launch-check")).andExpect(status().isOk()).andReturn());

        assertThat(fragment).contains("Prima di avviare").contains("Servono almeno 4 immagini (ce ne sono 0)").contains("every 6s")
                .doesNotContain("/start").doesNotContain("<html");
    }

    @Test
    void theLaunchCheckOfAMissingDraftOrASnapshotEndsQuietly() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);

        for (String url : new String[] {"/trainings/datasets/999999/launch-check", "/trainings/datasets/" + done.getSnapshotDatasetId() + "/launch-check"}) {
            String empty = body(mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn());
            assertThat(empty).contains("id=\"training-launch-check\"").doesNotContain("hx-get");
        }
    }

    @Test
    void savingTheLaunchSettingsPersistsThemAndAnswersWithTheRecomputedCheck() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        String fragment = body(mockMvc.perform(post("/trainings/datasets/" + id + "/launch-settings").header("HX-Request", "true")
                .param("modelName", " gatto ").param("trainingSteps", "1500").param("seed", "42").param("hfRepoName", "mio-repo"))
                .andExpect(status().isOk()).andReturn());

        // checkbox assenti = spenti
        assertThat(datasets.get(id).launchSettings()).isEqualTo(new LaunchSettings("gatto", 1500, 42L, false, null, "mio-repo", false));
        assertThat(fragment).contains("id=\"training-launch-check\"").doesNotContain("<html");
    }

    @Test
    void theCheckboxesTurnTheHuggingFaceCopyOnAndMakeTheRepoPrivate() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        mockMvc.perform(post("/trainings/datasets/" + id + "/launch-settings").header("HX-Request", "true")
                .param("trainingSteps", "1000").param("hfPublish", "true").param("hfPrivate", "true")).andExpect(status().isOk());

        assertThat(datasets.get(id).isHfPublish()).isTrue();
        assertThat(datasets.get(id).isHfPrivate()).isTrue();
    }

    @Test
    void settingsOutOfTheLimitsAreAnExpectedRefusalAndChangeNothing() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        mockMvc.perform(post("/trainings/datasets/" + id + "/launch-settings").header("HX-Request", "true").param("trainingSteps", "5"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/trainings/datasets/" + id + "/launch-settings").header("HX-Request", "true").param("trainingSteps", "abc"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/trainings/datasets/" + id + "/launch-settings").header("HX-Request", "true").param("trainingSteps", "1000").param("seed", "x"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(datasets.get(id).launchSettings()).isEqualTo(LaunchSettings.defaults());
    }

    @Test
    void withoutJavascriptSavingTheSettingsRedirectsBackToTheEditor() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        mockMvc.perform(post("/trainings/datasets/" + id + "/launch-settings").param("trainingSteps", "1200"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/trainings/datasets/" + id));

        assertThat(datasets.get(id).getTrainingSteps()).isEqualTo(1200);
    }

    @Test
    void startLaunchesAReadyDraftAndRedirectsToTheTrainingPage() throws Exception {
        Long id = readyDraft();

        MvcResult result = mockMvc.perform(post("/trainings/datasets/" + id + "/start").header("HX-Request", "true")).andExpect(status().isOk()).andReturn();

        Training created = trainingStore.findPage(0, 10).content().get(0);
        assertThat(result.getResponse().getHeader("HX-Redirect")).isEqualTo("/trainings/" + created.getId());
        assertThat(created.getSourceDatasetId()).isEqualTo(id);
        verify(trainer).createTraining(anyString(), anyString(), anyMap());
        verifyNoInteractions(huggingFace);
    }

    @Test
    void startOfADraftThatIsNotReadyIsAnExpectedRefusalAndSpendsNothing() throws Exception {
        Long id = datasets.create("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null).getId();

        mockMvc.perform(post("/trainings/datasets/" + id + "/start").header("HX-Request", "true")).andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(trainer);
        assertThat(trainingStore.findPage(0, 10).isEmpty()).isTrue();
    }

    @Test
    void theEditorOfARunningDraftAndASecondStartAreBlocked() throws Exception {
        Long id = readyDraft();
        mockMvc.perform(post("/trainings/datasets/" + id + "/start").header("HX-Request", "true")).andExpect(status().isOk());

        String fragment = body(mockMvc.perform(get("/trainings/datasets/" + id + "/launch-check")).andExpect(status().isOk()).andReturn());
        assertThat(fragment).contains("Un training di questa bozza e&#39; gia&#39; in corso").doesNotContain("/start");
        mockMvc.perform(post("/trainings/datasets/" + id + "/start").header("HX-Request", "true")).andExpect(status().isUnprocessableEntity());

        verify(trainer, times(1)).createTraining(anyString(), anyString(), anyMap());
    }

    @Test
    void theEditorOfASnapshotHasNoLaunchPanelButLinksToItsTraining() throws Exception {
        Training done = training(TrainingStatus.SUCCEEDED);

        String page = body(mockMvc.perform(get("/trainings/datasets/" + done.getSnapshotDatasetId())).andExpect(status().isOk()).andReturn());

        assertThat(page).doesNotContain("launch-settings").doesNotContain("training-launch-check")
                .contains("href=\"/trainings/" + done.getId() + "\"").contains("Apri il training #" + done.getId());
    }

    // --- caricamento a mano dei pesi su HuggingFace --------------------------------------------------------------

    /** Un training riuscito col risultato completo ma la copia su HuggingFace non trovata: il caso in cui il trainer non ha caricato nulla. */
    private Training succeededWithMissingCopy() {
        Training training = succeededWithResult(1L);
        training.setHfStatus(HfStatus.NOT_FOUND);
        return trainingStore.save(training);
    }

    private Long hfToken() {
        return tokens.create("HUGGINGFACE", "hf-upload-test", "hf_test_value_1234", null).id();
    }

    @Test
    void aMissingCopyOffersTheManualUploadWithTheSavedTokensAndDoesNotPoll() throws Exception {
        hfToken();
        Training done = succeededWithMissingCopy();

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Carica i pesi su HuggingFace").contains("sandro/il-mio-gatto").contains("name=\"hfTokenId\"").contains("hf-upload-test")
                .contains("hx-post=\"/trainings/" + done.getId() + "/hf-upload\"").contains("hx-include=\"#hf-upload-token\"").contains("Carica su HuggingFace")
                .doesNotContain("every 5s");
    }

    @Test
    void withoutASavedTokenTheUploadSaysWhereToAddOneInsteadOfShowingAButton() throws Exception {
        Training done = succeededWithMissingCopy();

        String page = body(mockMvc.perform(get("/trainings/" + done.getId())).andExpect(status().isOk()).andReturn());

        assertThat(page).contains("Carica i pesi su HuggingFace").contains("Nessun token salvato: aggiungine uno in /tokens").doesNotContain("hf-upload\"");
    }

    @Test
    void aVerifiedCopyOrNoCopyAtAllOffersNoUpload() throws Exception {
        hfToken();
        Training verified = succeededWithResult(1L);
        Training noCopy = succeededWithResult(1L);
        noCopy.setHfStatus(HfStatus.NONE);
        trainingStore.save(noCopy);

        assertThat(body(mockMvc.perform(get("/trainings/" + verified.getId())).andReturn())).doesNotContain("Carica i pesi su HuggingFace");
        assertThat(body(mockMvc.perform(get("/trainings/" + noCopy.getId())).andReturn())).doesNotContain("Carica i pesi su HuggingFace");
    }

    /** Il percorso intero con i collaboratori esterni finti: richiesta, thread in background, esito sulla riga. L'upload e' tenuto fermo per vedere lo stato "in corso". */
    @Test
    void theUploadRunsInTheBackgroundShowsItsProgressAndEndsWithTheCopyVerified() throws Exception {
        Long tokenId = hfToken();
        Training done = succeededWithMissingCopy();
        when(huggingFace.whoami("hf_test_value_1234")).thenReturn(new HfAccount("sandro", "write"));
        when(trainer.weights("train-1")).thenReturn(Optional.of(mock(WeightsFile.class)));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(i -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(huggingFace).uploadWeights(anyString(), anyString(), any(WeightsFile.class), anyString());

        try {
            String progress = body(mockMvc.perform(post("/trainings/" + done.getId() + "/hf-upload").param("hfTokenId", String.valueOf(tokenId))
                    .header("HX-Request", "true")).andExpect(status().isOk()).andReturn());

            assertThat(progress).contains("id=\"training-status\"").contains("Caricamento su HuggingFace in corso").contains("Caricamento in corso")
                    .contains("hx-trigger=\"every 5s\"").doesNotContain("Carica i pesi su HuggingFace").doesNotContain("hf-upload\"");
            verify(huggingFace, timeout(5000)).uploadWeights(org.mockito.ArgumentMatchers.eq("hf_test_value_1234"),
                    org.mockito.ArgumentMatchers.eq("sandro/il-mio-gatto"), any(WeightsFile.class), anyString());
            // un secondo clic mentre il primo e' in corso e' un rifiuto, non un secondo upload
            mockMvc.perform(post("/trainings/" + done.getId() + "/hf-upload").param("hfTokenId", String.valueOf(tokenId)).header("HX-Request", "true"))
                    .andExpect(status().is4xxClientError());
        } finally {
            release.countDown();
        }

        long deadline = System.currentTimeMillis() + 10_000;
        while (trainingStore.findById(done.getId()).orElseThrow().getHfStatus() != HfStatus.VERIFIED && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(trainingStore.findById(done.getId()).orElseThrow().getHfStatus()).isEqualTo(HfStatus.VERIFIED);
        verify(huggingFace, times(1)).uploadWeights(anyString(), anyString(), any(WeightsFile.class), anyString());
    }

    @Test
    void aReadOnlyTokenIsARejectionWithAToastAndNothingIsDownloaded() throws Exception {
        Long tokenId = hfToken();
        Training done = succeededWithMissingCopy();
        when(huggingFace.whoami("hf_test_value_1234")).thenReturn(new HfAccount("sandro", "read"));

        mockMvc.perform(post("/trainings/" + done.getId() + "/hf-upload").param("hfTokenId", String.valueOf(tokenId)).header("HX-Request", "true"))
                .andExpect(status().is4xxClientError()).andExpect(header().exists("HX-Trigger"));

        verify(trainer, never()).weights(anyString());
        verify(huggingFace, never()).uploadWeights(anyString(), anyString(), any(WeightsFile.class), anyString());
    }

    @Test
    void uploadingATrainingThatIsNotEligibleIsRejected() throws Exception {
        Long tokenId = hfToken();
        Training verified = succeededWithResult(1L);

        mockMvc.perform(post("/trainings/" + verified.getId() + "/hf-upload").param("hfTokenId", String.valueOf(tokenId)).header("HX-Request", "true"))
                .andExpect(status().is4xxClientError());

        verify(huggingFace, never()).uploadWeights(anyString(), anyString(), any(WeightsFile.class), anyString());
    }
}
