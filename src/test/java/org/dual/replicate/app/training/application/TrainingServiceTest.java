package org.dual.replicate.app.training.application;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.training.domain.ArchiveItem;
import org.dual.replicate.app.training.domain.DatasetArchive;
import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.HfStatus;
import org.dual.replicate.app.training.domain.HuggingFaceException;
import org.dual.replicate.app.training.domain.LaunchCheck;
import org.dual.replicate.app.training.domain.LaunchSettings;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.TrainerJob;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingImage;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.domain.event.TrainingChangedEvent;
import org.dual.replicate.app.training.domain.event.TrainingCompletedEvent;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.app.training.port.out.IDatasetArchiver;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.app.training.port.out.ITrainerGateway;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Il lancio e la vita di un training con tutti i collaboratori finti: NESSUNA chiamata a Replicate o HuggingFace, nessun file. Quel che si prova e' soprattutto
 * cio' che tocca i soldi e lo storico: un solo lancio per bozza, l'ordine delle scritture esterne, niente orfani quando un passo fallisce, il token che non
 * finisce da nessuna parte, un training riuscito che non diventa "annullato".
 */
class TrainingServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private static final Long DRAFT_ID = 1L;
    private static final Long SNAPSHOT_ID = 99L;
    private static final Long TOKEN_ID = 7L;
    private static final String HF_SECRET = "hf_SUPER_SECRET_value";
    private static final String DESTINATION_NAME = "il-mio-gatto-20261005-100000";
    private static final Duration TIMEOUT = Duration.ofHours(2);

    private final InMemoryTrainingStore store = new InMemoryTrainingStore();
    private final ITrainingDatasets datasets = mock(ITrainingDatasets.class);
    private final ITrainerGateway trainer = mock(ITrainerGateway.class);
    private final IHuggingFaceRepos huggingFace = mock(IHuggingFaceRepos.class);
    private final IDatasetArchiver archiver = mock(IDatasetArchiver.class);
    private final IApiTokens tokens = mock(IApiTokens.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final Messages messages = mock(Messages.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeArchive archive = new FakeArchive();
    private TrainingDataset draft;
    private TrainingService service;

    @BeforeEach
    void setUp() {
        // Il testo e' la chiave: basta a distinguere i rifiuti.
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        when(datasets.minSteps()).thenReturn(100);
        when(datasets.maxSteps()).thenReturn(4000);

        draft = dataset(DRAFT_ID, false, null, 5);
        when(datasets.get(DRAFT_ID)).thenAnswer(i -> draft);
        when(datasets.snapshot(DRAFT_ID)).thenAnswer(i -> snapshotOf(draft));

        when(tokens.get(TOKEN_ID)).thenReturn(new IApiTokens.TokenView(TOKEN_ID, "HUGGINGFACE", "hf", "alue", null, IApiTokens.Status.OK));
        when(tokens.resolve(TOKEN_ID, "HUGGINGFACE")).thenReturn(HF_SECRET);
        when(huggingFace.whoami(HF_SECRET)).thenReturn(new HfAccount("sandro", "write"));

        when(archiver.build(any())).thenReturn(archive);
        when(trainer.trainerVersion()).thenReturn("v-trainer");
        when(trainer.uploadFile(anyString(), any(DatasetArchive.class))).thenReturn("https://api.replicate.com/v1/files/abc");
        when(trainer.ensureDestination(anyString())).thenAnswer(i -> "acct/" + i.getArgument(0));
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenReturn(job("train-1", TrainingStatus.PENDING));

        service = new TrainingService(store, datasets, trainer, huggingFace, archiver, tokens, systemEvents, messages, publisher, clock, 4, 10, TIMEOUT);
    }

    // --- lancio: il caso buono ----------------------------------------------------------------------------------

    @Test
    void startRunsTheExternalStepsInASafeOrderAndSavesTheTraining() {
        draft.applyLaunchSettings(new LaunchSettings("Il mio gatto", 1200, 42L, true, TOKEN_ID, null, true), NOW);

        Training training = service.start(DRAFT_ID);

        // Prima cio' che non lascia nulla sugli account, poi il piu' fragile fra le scritture (repo HF), poi il modello, infine il training.
        InOrder order = inOrder(trainer, huggingFace, archiver);
        order.verify(trainer).trainerVersion();
        order.verify(archiver).build(any());
        order.verify(trainer).uploadFile(eq("dataset.zip"), any(DatasetArchive.class));
        order.verify(huggingFace).createModelRepo(HF_SECRET, DESTINATION_NAME, true);
        order.verify(trainer).ensureDestination(DESTINATION_NAME);
        order.verify(trainer).createTraining(eq("v-trainer"), eq("acct/" + DESTINATION_NAME), anyMap());

        assertThat(training.getId()).isNotNull();
        assertThat(store.rows).containsValue(training);
        assertThat(training.getExternalId()).isEqualTo("train-1");
        assertThat(training.getStatus()).isEqualTo(TrainingStatus.PENDING);
        assertThat(training.getSnapshotDatasetId()).as("il training ha il PROPRIO dataset congelato").isEqualTo(SNAPSHOT_ID);
        assertThat(training.getSourceDatasetId()).isEqualTo(DRAFT_ID);
        assertThat(training.getImageCount()).isEqualTo(5);
        assertThat(training.getTrainerVersion()).isEqualTo("v-trainer");
        assertThat(training.destinationModel()).isEqualTo("acct/" + DESTINATION_NAME);
        assertThat(training.getTrainingSteps()).isEqualTo(1200);
        assertThat(training.getSeed()).isEqualTo(42L);
        assertThat(training.isHfPublish()).isTrue();
        assertThat(training.getHfRepoId()).as("il repo di default ha il nome unico del modello di destinazione").isEqualTo("sandro/" + DESTINATION_NAME);
        assertThat(training.getHfStatus()).isEqualTo(HfStatus.PENDING);
        assertThat(training.getCreatedAt()).isEqualTo(NOW);
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
        assertThat(archive.closed).as("lo zip temporaneo si elimina sempre").isTrue();
    }

    @Test
    void theDraftNameIsUsedWhenThereIsNoModelNameAndTheStampMakesEveryDestinationUnique() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);

        service.start(DRAFT_ID);
        clock.set(NOW.plusSeconds(61));
        store.rows.values().forEach(t -> ReflectionTestUtils.setField(t, "status", TrainingStatus.SUCCEEDED));
        service.start(DRAFT_ID);

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(trainer, times(2)).ensureDestination(names.capture());
        assertThat(names.getAllValues()).containsExactly("il-mio-gatto-20261005-100000", "il-mio-gatto-20261005-100101");
    }

    @Test
    void theTrainerInputHasTheHuggingFaceFieldsOnlyWhenTheCopyIsRequested() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), NOW);
        service.start(DRAFT_ID);
        Map<String, Object> withCopy = lastInput();

        assertThat(withCopy).containsEntry("input_images", "https://api.replicate.com/v1/files/abc")
                .containsEntry("trigger_word", "TOKCAT").containsEntry("lora_type", "subject").containsEntry("training_steps", 1000)
                .containsEntry("hf_repo_id", "sandro/" + DESTINATION_NAME).containsEntry("hf_token", HF_SECRET)
                .doesNotContainKey("seed");

        store.rows.values().forEach(t -> ReflectionTestUtils.setField(t, "status", TrainingStatus.SUCCEEDED));
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, 5L, false, null, null, true), NOW);
        clock.set(NOW.plusSeconds(10));
        service.start(DRAFT_ID);
        Map<String, Object> withoutCopy = lastInput();

        assertThat(withoutCopy).doesNotContainKeys("hf_repo_id", "hf_token").containsEntry("seed", 5L);
        verify(huggingFace, times(1)).whoami(anyString());
        verify(huggingFace, times(1)).createModelRepo(anyString(), anyString(), anyBoolean());
    }

    @Test
    void aStyleLoraIsSentAsStyle() {
        draft = dataset(DRAFT_ID, false, null, 5, LoraType.STYLE);
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);

        service.start(DRAFT_ID);

        assertThat(lastInput()).containsEntry("lora_type", "style");
    }

    @Test
    void theZipHasOneImageAndOneCaptionFilePerImageWithTheSameName() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        draft.getImages().get(1).writeCaption("  TOKCAT, seduto sul divano  ");

        service.start(DRAFT_ID);

        ArgumentCaptor<List<ArchiveItem>> items = ArgumentCaptor.forClass(List.class);
        verify(archiver).build(items.capture());
        assertThat(items.getValue()).hasSize(5);
        assertThat(items.getValue()).extracting(ArchiveItem::imageEntry).containsExactly("img_001.jpg", "img_002.jpg", "img_003.jpg", "img_004.jpg", "img_005.jpg");
        assertThat(items.getValue()).extracting(ArchiveItem::captionEntry).containsExactly("img_001.txt", "img_002.txt", "img_003.txt", "img_004.txt", "img_005.txt");
        assertThat(items.getValue()).extracting(ArchiveItem::filename).as("si leggono i file dello SNAPSHOT, non quelli della bozza")
                .allMatch(f -> f.startsWith("snap-"));
        assertThat(items.getValue().get(1).caption()).isEqualTo("TOKCAT, seduto sul divano");
    }

    // --- lancio: il token HuggingFace ---------------------------------------------------------------------------

    @Test
    void theHuggingFaceTokenIsInNoSavedFieldAndNoEvent() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), NOW);

        Training training = service.start(DRAFT_ID);

        ReflectionUtils.doWithFields(Training.class, field -> {
            ReflectionUtils.makeAccessible(field);
            assertThat(String.valueOf(field.get(training))).as("campo " + field.getName()).doesNotContain(HF_SECRET);
        }, field -> !Modifier.isStatic(field.getModifiers()));
        assertThat(training.toString()).doesNotContain(HF_SECRET);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publishEvent(events.capture());
        assertThat(events.getAllValues()).allSatisfy(e -> assertThat(e.toString()).doesNotContain(HF_SECRET));
        verify(systemEvents, never()).record(anyString(), any(), any());
    }

    @Test
    void aReadOnlyTokenIsRejectedBeforeAnySnapshotOrSpending() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), NOW);
        when(huggingFace.whoami(HF_SECRET)).thenReturn(new HfAccount("sandro", "read"));

        assertRejected(() -> service.start(DRAFT_ID), "training.error.hfReadOnly");

        verify(datasets, never()).snapshot(anyLong());
        verifyNoInteractions(trainer);
        verify(huggingFace, never()).createModelRepo(anyString(), anyString(), anyBoolean());
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aSharedHuggingFaceRepoNameIsHonouredButTheCheckWarnsThatRunsOverwriteEachOther() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, "Mio Gatto", true), NOW);

        assertThat(service.check(DRAFT_ID).warnings()).contains("training.check.hfRepoShared");
        Training training = service.start(DRAFT_ID);

        assertThat(training.getHfRepoId()).isEqualTo("sandro/mio-gatto");
        verify(huggingFace).createModelRepo(HF_SECRET, "mio-gatto", true);
    }

    @Test
    void thePublicRepoChoiceIsPassedOn() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, false), NOW);

        service.start(DRAFT_ID);

        verify(huggingFace).createModelRepo(HF_SECRET, DESTINATION_NAME, false);
    }

    // --- lancio: blocchi ----------------------------------------------------------------------------------------

    @Test
    void startIsRejectedBeforeAnyCallWhenTheDraftIsNotReady() {
        draft = dataset(DRAFT_ID, false, null, 3);
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);

        assertRejected(() -> service.start(DRAFT_ID), "training.check.tooFewImages");

        verify(datasets, never()).snapshot(anyLong());
        verifyNoInteractions(trainer, huggingFace, archiver);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void checkListsEveryBlockerAndWarning() {
        draft = dataset(DRAFT_ID, false, null, 5);
        draft.getImages().get(0).requestAutoCaption(true); // le didascalie del fixture sono "a mano": serve la richiesta esplicita
        draft.getImages().get(1).writeCaption(null);
        draft.getImages().get(2).writeCaption("un gatto senza la parola magica");
        draft.applyLaunchSettings(new LaunchSettings(null, 99, null, true, null, null, true), NOW);

        LaunchCheck check = service.check(DRAFT_ID);

        assertThat(check.isLaunchable()).isFalse();
        assertThat(check.blockers()).containsExactlyInAnyOrder("training.check.captionsPending", "training.check.captionsMissing",
                "training.check.stepsOutOfRange", "training.check.hfTokenRequired");
        assertThat(check.warnings()).containsExactlyInAnyOrder("training.check.fewImages", "training.check.triggerMissing");
    }

    @Test
    void checkOfAReadyDraftHasNoBlockersAndOnlyWarnsOnTheFewImages() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);

        LaunchCheck check = service.check(DRAFT_ID);

        assertThat(check.isLaunchable()).isTrue();
        assertThat(check.warnings()).containsExactly("training.check.fewImages");
    }

    @Test
    void anExpiredOrMissingHuggingFaceTokenBlocksTheLaunch() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), NOW);
        when(tokens.get(TOKEN_ID)).thenReturn(new IApiTokens.TokenView(TOKEN_ID, "HUGGINGFACE", "hf", "alue", null, IApiTokens.Status.EXPIRED));
        assertThat(service.check(DRAFT_ID).blockers()).containsExactly("training.check.hfTokenExpired");

        when(tokens.get(TOKEN_ID)).thenThrow(new TokenException("non trovato"));
        assertThat(service.check(DRAFT_ID).blockers()).containsExactly("training.check.hfTokenMissing");
        assertRejected(() -> service.start(DRAFT_ID), "training.check.hfTokenMissing");
    }

    @Test
    void aFrozenDatasetCannotBeLaunched() {
        draft = dataset(DRAFT_ID, true, null, 5);

        assertRejected(() -> service.start(DRAFT_ID), "training.error.frozen");

        verifyNoInteractions(trainer);
    }

    // --- lancio: un solo training per bozza ---------------------------------------------------------------------

    @Test
    void aSecondLaunchWhileOneIsRunningIsRejectedAndSpendsNothing() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        service.start(DRAFT_ID);

        assertThat(service.check(DRAFT_ID).blockers()).first().isEqualTo("training.check.alreadyRunning");
        assertRejected(() -> service.start(DRAFT_ID), "training.check.alreadyRunning");

        verify(trainer, times(1)).createTraining(anyString(), anyString(), anyMap());
        verify(datasets, times(1)).snapshot(DRAFT_ID);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void aFinishedTrainingDoesNotBlockTheNextLaunchOfTheSameDraft() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        Training first = service.start(DRAFT_ID);
        ReflectionTestUtils.setField(first, "status", TrainingStatus.FAILED);
        clock.set(NOW.plusSeconds(30));

        service.start(DRAFT_ID);

        verify(trainer, times(2)).createTraining(anyString(), anyString(), anyMap());
    }

    @Test
    void twoSimultaneousLaunchesOfTheSameDraftStartOnlyOneTraining() throws Exception {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger creations = new AtomicInteger();
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenAnswer(i -> {
            creations.incrementAndGet();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return job("train-1", TrainingStatus.PENDING);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Training> first = pool.submit(() -> service.start(DRAFT_ID));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Training> second = pool.submit(() -> service.start(DRAFT_ID));
            Thread.sleep(300); // il secondo deve restare in attesa del primo, non arrivare a createTraining
            assertThat(creations.get()).as("il secondo non e' ancora arrivato a spendere").isEqualTo(1);
            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).getExternalId()).isEqualTo("train-1");
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(TrainingException.class)
                    .cause().hasMessage("training.check.alreadyRunning");
            assertThat(creations.get()).isEqualTo(1);
            assertThat(store.rows).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- lancio: fallimenti, niente orfani ----------------------------------------------------------------------

    @Test
    void anUploadFailureDeletesTheSnapshotAndNothingElseIsCreated() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), NOW);
        when(trainer.uploadFile(anyString(), any(DatasetArchive.class))).thenThrow(new ReplicateException("giu'", null, Kind.TRANSIENT));

        assertThatThrownBy(() -> service.start(DRAFT_ID)).isInstanceOf(ReplicateException.class);

        verify(datasets).deleteSnapshot(SNAPSHOT_ID);
        verify(huggingFace, never()).createModelRepo(anyString(), anyString(), anyBoolean());
        verify(trainer, never()).ensureDestination(anyString());
        verify(trainer, never()).createTraining(anyString(), anyString(), anyMap());
        assertThat(store.rows).isEmpty();
        assertThat(archive.closed).as("lo zip si elimina anche se il caricamento fallisce").isTrue();
    }

    @Test
    void aFailedHuggingFaceRepoMeansNoReplicateModelAndNoTraining() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), NOW);
        doThrow(new HuggingFaceException("403", null, Kind.PERMANENT)).when(huggingFace).createModelRepo(anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> service.start(DRAFT_ID)).isInstanceOf(HuggingFaceException.class);

        verify(trainer, never()).ensureDestination(anyString());
        verify(trainer, never()).createTraining(anyString(), anyString(), anyMap());
        verify(datasets).deleteSnapshot(SNAPSHOT_ID);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aFailedTrainingCreationDeletesTheSnapshotAndSavesNoRow() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenThrow(new ReplicateException("402", null, Kind.PERMANENT));

        assertThatThrownBy(() -> service.start(DRAFT_ID)).isInstanceOf(ReplicateException.class);

        verify(datasets).deleteSnapshot(SNAPSHOT_ID);
        verify(trainer, never()).cancelTraining(anyString());
        assertThat(store.rows).isEmpty();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void ifTheRowCannotBeSavedAfterTheRemoteStartTheTrainingIsCancelledAndTheSnapshotDeleted() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        store.failOnSave = new IllegalStateException("db giu'");

        assertThatThrownBy(() -> service.start(DRAFT_ID)).isInstanceOf(IllegalStateException.class).hasMessage("db giu'");

        verify(trainer).cancelTraining("train-1");
        verify(datasets).deleteSnapshot(SNAPSHOT_ID);
    }

    @Test
    void aFailedSnapshotCleanupDoesNotHideTheRealFailure() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        when(trainer.createTraining(anyString(), anyString(), anyMap())).thenThrow(new ReplicateException("402", null, Kind.PERMANENT));
        doThrow(new IllegalStateException("pulizia")).when(datasets).deleteSnapshot(SNAPSHOT_ID);

        assertThatThrownBy(() -> service.start(DRAFT_ID)).isInstanceOf(ReplicateException.class).hasMessage("402")
                .hasSuppressedException(new IllegalStateException("pulizia"));
    }

    @Test
    void theSnapshotIsCheckedAgainBecauseTheDraftMayHaveChangedMeanwhile() {
        draft.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), NOW);
        when(datasets.snapshot(DRAFT_ID)).thenAnswer(i -> {
            TrainingDataset snapshot = snapshotOf(draft);
            snapshot.getImages().get(0).writeCaption(null); // un'altra scheda ha svuotato una didascalia fra il controllo e la copia
            return snapshot;
        });

        assertRejected(() -> service.start(DRAFT_ID), "training.check.captionsMissing");

        verify(datasets).deleteSnapshot(SNAPSHOT_ID);
        verifyNoInteractions(trainer);
        assertThat(store.rows).isEmpty();
    }

    // --- avanzamento --------------------------------------------------------------------------------------------

    @Test
    void refreshOfATerminalTrainingDoesNotCallTheService() {
        Training training = running(TrainingStatus.SUCCEEDED);

        assertThat(service.refresh(training.getId())).isSameAs(training);

        verifyNoInteractions(trainer);
    }

    @Test
    void refreshStoresTheProgressAndNotifiesOnlyWhenTheStatusChanges() {
        Training training = running(TrainingStatus.PENDING);
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.PROCESSING, null, "step 10", null));

        service.refresh(training.getId());

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.PROCESSING);
        assertThat(training.getLogs()).isEqualTo("step 10");
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));

        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.PROCESSING, null, "step 20", null));
        service.refresh(training.getId());

        assertThat(training.getLogs()).isEqualTo("step 20");
        verify(publisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void aSucceededTrainingRecordsTheComputeTimeAndTheCompletion() {
        Training training = running(TrainingStatus.PROCESSING);
        clock.set(NOW.plusSeconds(600));
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "done", 512.5));

        service.refresh(training.getId());

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.SUCCEEDED);
        assertThat(training.getPredictTimeSeconds()).isEqualTo(512.5);
        assertThat(training.getCompletedAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(training.getLogs()).isEqualTo("done");
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
    }

    /** Da qui parte il risultato (preset, modello, copia su HuggingFace): una volta sola, qualunque strada porti a "riuscito". */
    @Test
    void aTrainingThatSucceedsPublishesTheCompletionOnceAndOnlyOnTheTransition() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "ok", 5.0));

        service.refresh(training.getId());
        service.refresh(training.getId()); // gia' terminale: nessuna chiamata, nessun secondo evento

        verify(publisher, times(1)).publishEvent(new TrainingCompletedEvent(training.getId()));
        verify(trainer, times(1)).getTraining(anyString());
    }

    @Test
    void aTrainingThatFailsOrIsCancelledDoesNotPublishTheCompletion() {
        Training failed = running(TrainingStatus.PROCESSING);
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.FAILED, "boom", null, null));
        service.refresh(failed.getId());
        Training cancelled = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(job("train-1", TrainingStatus.CANCELED));
        service.cancel(cancelled.getId());

        verify(publisher, never()).publishEvent(any(TrainingCompletedEvent.class));
    }

    @Test
    void aCancelThatFindsTheTrainingAlreadySucceededStillTriggersTheResult() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "ok", 9.0));

        service.cancel(training.getId());

        verify(publisher).publishEvent(new TrainingCompletedEvent(training.getId()));
    }

    @Test
    void aFailedTrainingKeepsTheErrorAndWarnsTheUser() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.FAILED, "CUDA out of memory", "tb", 3.0));

        service.refresh(training.getId());

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.FAILED);
        assertThat(training.getErrorMessage()).isEqualTo("CUDA out of memory");
        verify(systemEvents).warn(any(), eq("trainingFailed"), eq("training:" + training.getId()), anyString());
    }

    @Test
    void aFailedTrainingWithoutAMessageGetsAGenericOne() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.FAILED, " ", null, null));

        service.refresh(training.getId());

        assertThat(training.getErrorMessage()).isEqualTo("training.error.failedGeneric");
    }

    @Test
    void aTransientPollFailureLeavesTheTrainingRunningAndIsRecorded() {
        Training training = running(TrainingStatus.PROCESSING);
        ReplicateException blip = new ReplicateException("timeout", null, Kind.TRANSIENT);
        when(trainer.getTraining("train-1")).thenThrow(blip);

        Training result = service.refresh(training.getId());

        assertThat(result.getStatus()).as("il training su Replicate continua: un'interruzione di pochi secondi non lo chiude").isEqualTo(TrainingStatus.PROCESSING);
        verify(systemEvents).record("getTraining", blip, "training:" + training.getId());
        verify(trainer, never()).cancelTraining(anyString());
    }

    /** Il token di Replicate tolto dalla configurazione e' un problema di chi gestisce l'app, non del training, che su Replicate gira comunque. */
    @Test
    void aConfigurationErrorOnPollLeavesTheTrainingRunningAndIsRecorded() {
        Training training = running(TrainingStatus.PROCESSING);
        ReplicateException noToken = new ReplicateException("REPLICATE_API_TOKEN non impostato", null, Kind.CONFIGURATION);
        when(trainer.getTraining("train-1")).thenThrow(noToken);

        Training result = service.refresh(training.getId());

        assertThat(result.getStatus()).isEqualTo(TrainingStatus.PROCESSING);
        verify(systemEvents).record("getTraining", noToken, "training:" + training.getId());
        verify(trainer, never()).cancelTraining(anyString());
    }

    @Test
    void aPermanentPollFailureFailsTheTrainingAndStopsItRemotely() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.getTraining("train-1")).thenThrow(new ReplicateException("404", null, Kind.PERMANENT));
        when(trainer.cancelTraining("train-1")).thenReturn(job("train-1", TrainingStatus.CANCELED));

        service.refresh(training.getId());

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.FAILED);
        assertThat(training.getErrorMessage()).isEqualTo("training.error.contactFailed");
        verify(trainer).cancelTraining("train-1");
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
    }

    @Test
    void theBusinessTimeoutFailsAStillRunningTrainingAndCancelsItRemotely() {
        Training training = running(TrainingStatus.PROCESSING);
        clock.set(NOW.plus(TIMEOUT).plusSeconds(1));
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.PROCESSING, null, "step 900", null));

        service.refresh(training.getId());

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.FAILED);
        assertThat(training.getErrorMessage()).isEqualTo("training.error.timeout");
        verify(trainer).cancelTraining("train-1");
        assertThat(service.isOverdue(running(TrainingStatus.PROCESSING))).as("il nuovo training e' stato creato 'adesso' = dopo il timeout dell'orologio avanzato").isTrue();
    }

    @Test
    void theBusinessTimeoutAppliesEvenWhenTheServiceKeepsFailingTransiently() {
        Training training = running(TrainingStatus.PROCESSING);
        clock.set(NOW.plus(TIMEOUT).plusSeconds(1));
        when(trainer.getTraining("train-1")).thenThrow(new ReplicateException("timeout", null, Kind.TRANSIENT));

        service.refresh(training.getId());

        assertThat(training.getStatus()).as("non esiste attesa infinita").isEqualTo(TrainingStatus.FAILED);
        verify(trainer).cancelTraining("train-1");
    }

    @Test
    void aTrainingThatFinishesJustBeforeTheTimeoutIsNotFailed() {
        Training training = running(TrainingStatus.PROCESSING);
        clock.set(NOW.plus(TIMEOUT).plusSeconds(1));
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "ok", 1.0));

        service.refresh(training.getId());

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.SUCCEEDED);
        verify(trainer, never()).cancelTraining(anyString());
    }

    @Test
    void anUnknownTrainingIsARejectionNotAnInternalError() {
        assertRejected(() -> service.refresh(404L), "training.error.trainingNotFound");
        assertRejected(() -> service.get(null), "training.error.trainingNotFound");
    }

    // --- annullamento -------------------------------------------------------------------------------------------

    @Test
    void cancelMarksTheTrainingCancelledWhenReplicateConfirmsIt() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(job("train-1", TrainingStatus.CANCELED));

        Training result = service.cancel(training.getId());

        assertThat(result.getStatus()).isEqualTo(TrainingStatus.CANCELED);
        assertThat(result.getCompletedAt()).isEqualTo(NOW);
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
        verify(systemEvents, never()).warn(any(), anyString(), any(), anyString());
    }

    @Test
    void cancelMarksItCancelledEvenIfReplicateStillShowsItRunning() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(job("train-1", TrainingStatus.PROCESSING));

        assertThat(service.cancel(training.getId()).getStatus()).isEqualTo(TrainingStatus.CANCELED);
    }

    @Test
    void aTrainingThatFinishedJustBeforeTheCancelStaysSucceeded() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "ok", 99.0));

        Training result = service.cancel(training.getId());

        assertThat(result.getStatus()).as("l'esito vero e' 'riuscito': non diventa 'annullato' e il suo risultato si usa").isEqualTo(TrainingStatus.SUCCEEDED);
        assertThat(result.getPredictTimeSeconds()).isEqualTo(99.0);
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void aTrainingThatFailedJustBeforeTheCancelKeepsItsOwnError() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.FAILED, "boom", null, null));

        Training result = service.cancel(training.getId());

        assertThat(result.getStatus()).isEqualTo(TrainingStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void cancelOfAFinishedTrainingIsRejectedWithoutCallingTheService() {
        Training training = running(TrainingStatus.FAILED);

        assertRejected(() -> service.cancel(training.getId()), "training.error.alreadyFinished");

        verifyNoInteractions(trainer);
    }

    @Test
    void ifTheCancelIsRefusedPermanentlyTheRealStateIsReadInstead() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenThrow(new ReplicateException("409 gia' terminale", null, Kind.PERMANENT));
        when(trainer.getTraining("train-1")).thenReturn(new TrainerJob("train-1", TrainingStatus.SUCCEEDED, null, "ok", 5.0));

        assertThat(service.cancel(training.getId()).getStatus()).isEqualTo(TrainingStatus.SUCCEEDED);
    }

    @Test
    void ifTheCancelFailsTransientlyNothingChangesAndTheErrorReachesTheCaller() {
        Training training = running(TrainingStatus.PROCESSING);
        when(trainer.cancelTraining("train-1")).thenThrow(new ReplicateException("timeout", null, Kind.TRANSIENT));

        assertThatThrownBy(() -> service.cancel(training.getId())).isInstanceOf(ReplicateException.class);

        assertThat(training.getStatus()).isEqualTo(TrainingStatus.PROCESSING);
    }

    // --- eliminazione -------------------------------------------------------------------------------------------

    @Test
    void deleteOfARunningTrainingCancelsItThenRemovesTheRowAndTheSnapshot() {
        Training training = running(TrainingStatus.PROCESSING);

        service.delete(training.getId());

        InOrder order = inOrder(trainer, datasets);
        order.verify(trainer).cancelTraining("train-1");
        order.verify(datasets).deleteSnapshot(SNAPSHOT_ID);
        assertThat(store.rows).isEmpty();
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
    }

    @Test
    void deleteOfAFinishedTrainingDoesNotTouchTheService() {
        Training training = running(TrainingStatus.SUCCEEDED);

        service.delete(training.getId());

        verifyNoInteractions(trainer, huggingFace);
        verify(datasets).deleteSnapshot(SNAPSHOT_ID);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void deleteStillRemovesTheRowWhenTheSnapshotIsAlreadyGone() {
        Training training = running(TrainingStatus.SUCCEEDED);
        doThrow(new TrainingException("non trovato")).when(datasets).deleteSnapshot(SNAPSHOT_ID);

        service.delete(training.getId());

        assertThat(store.rows).isEmpty();
        verify(systemEvents, never()).record(anyString(), any(), any());
    }

    @Test
    void aRealFailureDeletingTheSnapshotIsRecordedButTheRowIsGone() {
        Training training = running(TrainingStatus.SUCCEEDED);
        TrainingException failure = new TrainingException("disco", null, Kind.PERMANENT);
        doThrow(failure).when(datasets).deleteSnapshot(SNAPSHOT_ID);

        service.delete(training.getId());

        assertThat(store.rows).isEmpty();
        verify(systemEvents).record("deleteTrainingSnapshot", failure, "training:" + training.getId());
    }

    // --- elenco -------------------------------------------------------------------------------------------------

    @Test
    void inProgressListsOnlyNonTerminalTrainingsAndFindBySnapshotFindsTheirDataset() {
        Training pending = running(TrainingStatus.PENDING);
        running(TrainingStatus.SUCCEEDED);
        running(TrainingStatus.FAILED);
        running(TrainingStatus.CANCELED);

        assertThat(service.inProgress()).containsExactly(pending);
        assertThat(service.findBySnapshot(SNAPSHOT_ID)).contains(pending);
        assertThat(service.findBySnapshot(12345L)).isEmpty();
        assertThat(service.findBySnapshot(null)).isEmpty();
    }

    @Test
    void theSlugIsAValidModelName() {
        assertThat(TrainingService.slug("Il mio Gatto!")).isEqualTo("il-mio-gatto");
        assertThat(TrainingService.slug("  --Ciao__mondo--  ")).isEqualTo("ciao-mondo");
        assertThat(TrainingService.slug("!!!")).as("mai vuoto").isEqualTo("lora");
        assertThat(TrainingService.slug(null)).isEqualTo("lora");
        assertThat(TrainingService.slug("x".repeat(100))).hasSize(40);
        assertThat(TrainingService.slug("a".repeat(39) + "-bbb")).as("niente trattino finale dopo il taglio").isEqualTo("a".repeat(39));
    }

    // --- aiuti --------------------------------------------------------------------------------------------------

    private Map<String, Object> lastInput() {
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(trainer, atLeastOnce()).createTraining(anyString(), anyString(), input.capture());
        return input.getValue();
    }

    /** Un training salvato nello stato dato (per le prove di avanzamento, annullamento ed eliminazione), come se fosse partito da {@code DRAFT_ID}. */
    private Training running(TrainingStatus status) {
        TrainingDataset snapshot = snapshotOf(draft);
        Training training = new Training(snapshot, "train-1", status, "v-trainer", "acct", DESTINATION_NAME, null, NOW);
        return store.save(training);
    }

    private static TrainerJob job(String id, TrainingStatus status) {
        return new TrainerJob(id, status, null, null, null);
    }

    private static TrainingDataset dataset(Long id, boolean frozen, Long sourceId, int images) {
        return dataset(id, frozen, sourceId, images, LoraType.SUBJECT);
    }

    private static TrainingDataset dataset(Long id, boolean frozen, Long sourceId, int images, LoraType type) {
        TrainingDataset dataset = new TrainingDataset("Il mio gatto", "TOKCAT", type, null, frozen, sourceId, NOW);
        ReflectionTestUtils.setField(dataset, "id", id);
        for (int i = 1; i <= images; i++) {
            TrainingImage image = dataset.addImage("draft-" + i + ".jpg", "gatto" + i + ".jpg", NOW);
            image.writeCaption("TOKCAT, foto numero " + i);
        }
        return dataset;
    }

    /** Lo snapshot che {@code ITrainingDatasets#snapshot} darebbe: congelato, con file propri (copie) e le stesse didascalie e impostazioni. */
    private static TrainingDataset snapshotOf(TrainingDataset source) {
        TrainingDataset snapshot = new TrainingDataset(source.getName(), source.getTriggerWord(), source.getLoraType(), source.getNote(), true,
                source.getId(), NOW);
        ReflectionTestUtils.setField(snapshot, "id", SNAPSHOT_ID);
        snapshot.copyLaunchSettingsFrom(source);
        int n = 1;
        for (TrainingImage image : source.getImages()) {
            snapshot.addCopyOf(image, "snap-" + n + ".jpg", "snap-orig-" + n + ".jpg", NOW);
            n++;
        }
        return snapshot;
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        assertThatThrownBy(call).isInstanceOfSatisfying(TrainingException.class, e -> {
            assertThat(e.getMessage()).isEqualTo(messageKey);
            assertThat(e.isReportable()).as("un rifiuto atteso non e' un evento di sistema").isFalse();
        });
    }

    /** Lo zip finto: tiene solo se e' stato chiuso. */
    private static final class FakeArchive implements DatasetArchive {

        boolean closed;

        @Override
        public long size() {
            return 1234;
        }

        @Override
        public java.io.InputStream open() {
            return new java.io.ByteArrayInputStream(new byte[] {1, 2, 3});
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Orologio che si sposta a comando: i timeout si provano senza aspettare. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
