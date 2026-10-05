package org.dual.replicate.app.training.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.app.generation.port.in.ILoraPresets.LoraView;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.app.training.domain.HfStatus;
import org.dual.replicate.app.training.domain.HuggingFaceException;
import org.dual.replicate.app.training.domain.LaunchSettings;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.ModelStatus;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.domain.event.TrainingChangedEvent;
import org.dual.replicate.app.training.port.in.ITrainingDatasets;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Il risultato di un training riuscito con tutti i collaboratori finti (nessuna rete, nessun preset o repo veri): preset, modello utilizzabile e copia su
 * HuggingFace sono tre passi indipendenti e idempotenti, nessuno dei quali fa mai fallire il training.
 */
class TrainingResultServiceTest {

    private static final Instant COMPLETED = Instant.parse("2026-10-05T12:00:00Z");
    private static final Long TOKEN_ID = 7L;
    private static final String MODEL = "acct/il-mio-gatto-20261005-100000";
    private static final String REPO = "sandro/il-mio-gatto-20261005-100000";

    private final InMemoryTrainingStore store = new InMemoryTrainingStore();
    private final ITrainingDatasets datasets = mock(ITrainingDatasets.class);
    private final ILoraPresets presets = mock(ILoraPresets.class);
    private final IModelCatalog catalog = mock(IModelCatalog.class);
    private final IHuggingFaceRepos huggingFace = mock(IHuggingFaceRepos.class);
    private final IApiTokens tokens = mock(IApiTokens.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final Messages messages = mock(Messages.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final MutableClock clock = new MutableClock(COMPLETED.plusSeconds(30));
    private TrainingDataset snapshot;
    private TrainingResultService service;

    @BeforeEach
    void setUp() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));

        snapshot = new TrainingDataset("Il mio gatto", "TOKCAT", LoraType.SUBJECT, null, true, 1L, COMPLETED);
        ReflectionTestUtils.setField(snapshot, "id", 99L);
        snapshot.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, TOKEN_ID, null, true), COMPLETED);
        when(datasets.find(99L)).thenReturn(Optional.of(snapshot));

        when(presets.list()).thenReturn(List.of());
        when(presets.create(anyString(), anyString(), anyDouble(), anyString(), anyString())).thenAnswer(i ->
                new LoraView(500L, i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4)));
        when(catalog.contains(MODEL)).thenReturn(true); // il preset ha gia' censito il modello (regola "preset = modello")
        when(tokens.resolve(TOKEN_ID, "HUGGINGFACE")).thenReturn("hf_secret");
        when(huggingFace.repoFiles("hf_secret", REPO)).thenReturn(Optional.of(List.of(".gitattributes", "lora.safetensors")));

        service = new TrainingResultService(store, datasets, presets, catalog, huggingFace, tokens, systemEvents, messages, publisher, clock,
                Duration.ofMinutes(10), Duration.ofHours(6));
    }

    private Training succeeded(boolean huggingFaceCopy) {
        snapshot.applyLaunchSettings(new LaunchSettings(null, 1000, null, huggingFaceCopy, huggingFaceCopy ? TOKEN_ID : null, null, true), COMPLETED);
        Training training = new Training(snapshot, "train-1", TrainingStatus.PROCESSING, "v", "acct", "il-mio-gatto-20261005-100000",
                huggingFaceCopy ? REPO : null, COMPLETED.minusSeconds(3600));
        training.succeed("ok", 100.0, COMPLETED);
        return store.save(training);
    }

    // --- il caso buono ------------------------------------------------------------------------------------------

    @Test
    void aSucceededTrainingGetsAPresetAUsableModelAndAVerifiedHuggingFaceCopy() {
        Training training = succeeded(true);

        service.complete(training.getId());

        verify(presets).create(eq("Il mio gatto"), eq(MODEL), eq(ILoraPresets.DEFAULT_SCALE), eq("TOKCAT"), eq("training.result.presetNote"));
        assertThat(training.getPresetId()).isEqualTo(500L);
        assertThat(training.getModelStatus()).isEqualTo(ModelStatus.REGISTERED);
        assertThat(training.getHfStatus()).isEqualTo(HfStatus.VERIFIED);
        assertThat(training.isResultIncomplete()).isFalse();
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
        verify(systemEvents, never()).warn(any(), anyString(), any(), anyString());
    }

    @Test
    void completingAgainDoesNothing() {
        Training training = succeeded(true);
        service.complete(training.getId());
        int saves = store.saves;

        service.complete(training.getId());

        verify(presets, times(1)).create(anyString(), anyString(), anyDouble(), anyString(), anyString());
        verify(huggingFace, times(1)).repoFiles(anyString(), anyString());
        verify(publisher, times(1)).publishEvent(any(Object.class));
        assertThat(store.saves).isEqualTo(saves);
    }

    @Test
    void withoutTheHuggingFaceCopyOnlyThePresetAndTheModelAreHandled() {
        Training training = succeeded(false);

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.NONE);
        assertThat(training.isResultIncomplete()).isFalse();
        verifyNoInteractions(huggingFace, tokens);
    }

    @Test
    void anUnknownNullOrNotSucceededTrainingIsIgnored() {
        Training failed = succeeded(true);
        ReflectionTestUtils.setField(failed, "status", TrainingStatus.FAILED);

        service.complete(null);
        service.complete(404L);
        service.complete(failed.getId());

        verifyNoInteractions(presets, catalog, huggingFace);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // --- 1. preset ----------------------------------------------------------------------------------------------

    @Test
    void aTakenNameGetsTheDateAsASuffixAndThenTheTime() {
        Training training = succeeded(false);
        when(presets.list()).thenReturn(List.of(view(1L, "Il mio gatto", "altro/modello")));

        service.complete(training.getId());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(presets).create(name.capture(), anyString(), anyDouble(), anyString(), anyString());
        assertThat(name.getValue()).startsWith("Il mio gatto (2026-10-05").endsWith(")").doesNotContain(":");
    }

    @Test
    void aNameTakenEvenWithTheDateGetsTheTimeAndThenTheTrainingId() {
        Training training = succeeded(false);
        Instant created = training.getCreatedAt();
        String dated = "Il mio gatto (" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(created) + ")";
        String timed = "Il mio gatto (" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(created) + ")";
        when(presets.list()).thenReturn(List.of(view(1L, "il mio GATTO", "x/a"), view(2L, dated, "x/b"), view(3L, timed, "x/c")));

        service.complete(training.getId());

        verify(presets).create(eq("Il mio gatto #" + training.getId()), anyString(), anyDouble(), anyString(), anyString());
    }

    @Test
    void aLongNameIsTrimmedToTheLimitAlsoWithASuffix() {
        TrainingDataset longSnapshot = new TrainingDataset("n".repeat(80), "TOK", LoraType.SUBJECT, null, true, 1L, COMPLETED);
        ReflectionTestUtils.setField(longSnapshot, "id", 98L);
        longSnapshot.applyLaunchSettings(new LaunchSettings(null, 1000, null, false, null, null, true), COMPLETED);
        Training training = new Training(longSnapshot, "t", TrainingStatus.PROCESSING, "v", "acct", "m", null, COMPLETED.minusSeconds(60));
        training.succeed("ok", 1.0, COMPLETED);
        store.save(training);
        when(catalog.contains("acct/m")).thenReturn(true);
        when(presets.list()).thenReturn(List.of(view(1L, "n".repeat(60), "x/a")));

        service.complete(training.getId());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(presets).create(name.capture(), anyString(), anyDouble(), anyString(), anyString());
        assertThat(name.getValue()).hasSizeLessThanOrEqualTo(ILoraPresets.MAX_NAME).contains("(2026-10-05");
    }

    @Test
    void aPresetAlreadyCreatedForTheSameSourceIsAdoptedInsteadOfCreatingASecondOne() {
        Training training = succeeded(false);
        when(presets.list()).thenReturn(List.of(view(321L, "Il mio gatto", MODEL)));

        service.complete(training.getId());

        verify(presets, never()).create(anyString(), anyString(), anyDouble(), anyString(), anyString());
        assertThat(training.getPresetId()).isEqualTo(321L);
    }

    @Test
    void aFailingPresetDoesNotBlockTheOtherStepsAndIsRetriedLater() {
        Training training = succeeded(true);
        RuntimeException broken = new IllegalStateException("db");
        doThrow(broken).when(presets).create(anyString(), anyString(), anyDouble(), anyString(), anyString());

        service.complete(training.getId());

        assertThat(training.getPresetId()).isNull();
        assertThat(training.getModelStatus()).isEqualTo(ModelStatus.REGISTERED);
        assertThat(training.getHfStatus()).isEqualTo(HfStatus.VERIFIED);
        assertThat(training.isResultIncomplete()).as("il preset resta da fare").isTrue();
        verify(systemEvents).record("completeTrainingPreset", broken, "training:" + training.getId());

        org.mockito.Mockito.reset(presets);
        when(presets.list()).thenReturn(List.of());
        when(presets.create(anyString(), anyString(), anyDouble(), anyString(), anyString())).thenAnswer(i ->
                new LoraView(501L, i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4)));
        service.complete(training.getId());

        assertThat(training.getPresetId()).isEqualTo(501L);
        assertThat(training.isResultIncomplete()).isFalse();
    }

    // --- 2. modello utilizzabile --------------------------------------------------------------------------------

    @Test
    void aModelNotYetInTheCatalogIsRegisteredExplicitly() {
        Training training = succeeded(false);
        when(catalog.contains(MODEL)).thenReturn(false);
        when(catalog.registerLoraFinetune(MODEL, "Il mio gatto")).thenReturn(Optional.of(mock(ReplicateModel.class)));

        service.complete(training.getId());

        assertThat(training.getModelStatus()).isEqualTo(ModelStatus.REGISTERED);
    }

    @Test
    void aRejectionRightAfterTheSuccessIsRetriedBecauseTheVersionMayNotBeVisibleYet() {
        Training training = succeeded(false);
        when(catalog.contains(MODEL)).thenReturn(false);
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenThrow(new ReplicateException("senza versioni"));

        service.complete(training.getId());

        assertThat(training.getModelStatus()).as("entro la tolleranza (10 minuti) resta da rifare, senza avvisi").isEqualTo(ModelStatus.PENDING);
        verify(systemEvents, never()).warn(any(), anyString(), any(), anyString());
        assertThat(training.isResultIncomplete()).isTrue();
    }

    @Test
    void aRejectionAfterTheGracePeriodIsFinalAndWarnsTheUser() {
        Training training = succeeded(false);
        when(catalog.contains(MODEL)).thenReturn(false);
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenThrow(new ReplicateException("manca lora_scale"));
        clock.set(COMPLETED.plus(Duration.ofMinutes(11)));

        service.complete(training.getId());

        assertThat(training.getModelStatus()).isEqualTo(ModelStatus.REJECTED);
        verify(systemEvents).warn(any(), eq("trainedModelUnusable"), eq("training:" + training.getId()), anyString());
        assertThat(training.isResultIncomplete()).as("non si ritenta all'infinito un modello che non si puo' censire").isFalse();
    }

    @Test
    void aModelRegisteredButDisabledIsRejectedImmediatelyBecauseRetryingDoesNotReactivateIt() {
        Training training = succeeded(false);
        when(catalog.contains(MODEL)).thenReturn(false);
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenReturn(Optional.empty());

        service.complete(training.getId());

        assertThat(training.getModelStatus()).isEqualTo(ModelStatus.REJECTED);
        verify(systemEvents).warn(any(), eq("trainedModelUnusable"), anyString(), anyString());
    }

    @Test
    void aServiceFailureWhileRegisteringLeavesItToRetryAndIsRecorded() {
        Training training = succeeded(false);
        when(catalog.contains(MODEL)).thenReturn(false);
        ReplicateException down = new ReplicateException("giu'", null, Kind.TRANSIENT);
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenThrow(down);
        clock.set(COMPLETED.plus(Duration.ofHours(1))); // anche oltre la tolleranza: un guasto del servizio non e' un rifiuto

        service.complete(training.getId());

        assertThat(training.getModelStatus()).isEqualTo(ModelStatus.PENDING);
        verify(systemEvents).record("registerTrainedModel", down, "training:" + training.getId());
        verify(systemEvents, never()).warn(any(), anyString(), any(), anyString());
    }

    // --- 3. copia su HuggingFace --------------------------------------------------------------------------------

    @Test
    void aRepoWithoutWeightFilesIsNotVerifiedOnceTheGracePeriodIsOver() {
        Training training = succeeded(true);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenReturn(Optional.of(List.of(".gitattributes", "README.md")));
        clock.set(COMPLETED.plus(Duration.ofMinutes(11)));

        service.complete(training.getId());

        assertThat(training.getHfStatus()).as("il repo c'e' (lo ha creato l'app) ma senza pesi").isEqualTo(HfStatus.NOT_FOUND);
        assertThat(training.getStatus()).as("un guasto della copia non tocca l'esito del training").isEqualTo(TrainingStatus.SUCCEEDED);
        verify(systemEvents).warn(any(), eq("hfWeightsMissing"), eq("training:" + training.getId()), anyString());
    }

    @Test
    void aMissingRepoIsNotFoundToo() {
        Training training = succeeded(true);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenReturn(Optional.empty());
        clock.set(COMPLETED.plus(Duration.ofMinutes(11)));

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.NOT_FOUND);
    }

    @Test
    void missingWeightsRightAfterTheSuccessAreRetriedBecauseTheListingMayLag() {
        Training training = succeeded(true);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenReturn(Optional.of(List.of(".gitattributes")));

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.PENDING);
        verify(systemEvents, never()).warn(any(), anyString(), any(), anyString());
        assertThat(training.getPresetId()).as("gli altri passi sono comunque fatti").isEqualTo(500L);
    }

    @Test
    void theWeightFileSuffixIsMatchedIgnoringCase() {
        Training training = succeeded(true);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenReturn(Optional.of(List.of("LORA.SafeTensors")));

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.VERIFIED);
    }

    @Test
    void aTokenThatIsGoneMakesTheCopyUnverifiedWithoutAnError() {
        Training training = succeeded(true);
        when(tokens.resolve(TOKEN_ID, "HUGGINGFACE")).thenThrow(new TokenException("scaduto"));

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.UNVERIFIED);
        verifyNoInteractions(huggingFace);
        verify(systemEvents, never()).record(anyString(), any(), any());
        assertThat(training.isResultIncomplete()).isFalse();
    }

    @Test
    void aSnapshotWithoutATokenIdMakesTheCopyUnverified() {
        Training training = succeeded(true);
        snapshot.applyLaunchSettings(new LaunchSettings(null, 1000, null, true, null, null, true), COMPLETED);

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.UNVERIFIED);
        verifyNoInteractions(huggingFace);
    }

    @Test
    void aTransientHuggingFaceFailureLeavesTheCheckToRetry() {
        Training training = succeeded(true);
        HuggingFaceException down = new HuggingFaceException("giu'", null, Kind.TRANSIENT);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenThrow(down);

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.PENDING);
        verify(systemEvents).record("verifyHuggingFaceRepo", down, "training:" + training.getId());
    }

    @Test
    void aPermanentHuggingFaceFailureMakesTheCopyUnverifiedAndIsRecorded() {
        Training training = succeeded(true);
        HuggingFaceException denied = new HuggingFaceException("401", null, Kind.PERMANENT);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenThrow(denied);

        service.complete(training.getId());

        assertThat(training.getHfStatus()).isEqualTo(HfStatus.UNVERIFIED);
        verify(systemEvents).record("verifyHuggingFaceRepo", denied, "training:" + training.getId());
    }

    @Test
    void theHuggingFaceTokenNeverReachesTheRowTheEventsOrTheMessages() {
        Training training = succeeded(true);
        when(huggingFace.repoFiles("hf_secret", REPO)).thenReturn(Optional.of(List.of(".gitattributes")));
        clock.set(COMPLETED.plus(Duration.ofMinutes(11)));

        service.complete(training.getId());

        assertThat(training.toString()).doesNotContain("hf_secret");
        ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
        verify(systemEvents).warn(any(), anyString(), any(), warning.capture());
        assertThat(warning.getValue()).doesNotContain("hf_secret");
    }

    // --- recupero -----------------------------------------------------------------------------------------------

    @Test
    void recoverPendingResumesOnlyTheRecentIncompleteResultsAndCountsThem() {
        Training recent = succeeded(false);
        Training complete = succeeded(false);
        complete.setPresetId(1L);
        complete.setModelStatus(ModelStatus.REGISTERED);
        Training old = succeeded(false);
        ReflectionTestUtils.setField(old, "completedAt", COMPLETED.minus(Duration.ofHours(7)));
        clock.set(COMPLETED.plus(Duration.ofMinutes(1)));

        int resumed = service.recoverPending();

        assertThat(resumed).isEqualTo(1);
        assertThat(recent.getPresetId()).isNotNull();
        assertThat(old.getPresetId()).as("oltre la finestra di ripresa non si insiste").isNull();
    }

    @Test
    void aFailureResumingOneDoesNotStopTheOthers() {
        Training first = succeeded(false);
        Training second = succeeded(false);
        ReflectionTestUtils.setField(second, "completedAt", COMPLETED.plusSeconds(5));
        store.failOnSave = new IllegalStateException("db");

        int resumed = service.recoverPending();

        assertThat(resumed).isEqualTo(2);
        verify(systemEvents, org.mockito.Mockito.atLeastOnce()).record(anyString(), any(), anyString());
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    private static LoraView view(Long id, String name, String source) {
        return new LoraView(id, name, source, 1.0, "tok", null);
    }

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
