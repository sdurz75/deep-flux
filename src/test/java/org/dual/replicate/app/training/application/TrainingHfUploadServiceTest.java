package org.dual.replicate.app.training.application;

import java.time.Instant;
import java.util.Optional;

import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.HfStatus;
import org.dual.replicate.app.training.domain.HuggingFaceException;
import org.dual.replicate.app.training.domain.LaunchSettings;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.domain.WeightsFile;
import org.dual.replicate.app.training.domain.event.HfUploadRequestedEvent;
import org.dual.replicate.app.training.domain.event.TrainingChangedEvent;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.app.training.port.out.ITrainerGateway;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.tokens.domain.TokenException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Il caricamento a mano dei pesi su HuggingFace con tutti i collaboratori finti: nessuna rete, nessun download, nessun repo vero. Il vincolo che conta e' che un
 * fallimento lasci la riga com'era (il bottone ricompare) e che un caricamento in corso non si possa avviare due volte.
 */
class TrainingHfUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Long TOKEN_ID = 7L;
    private static final String REPO = "sandro/test-lora";

    private final InMemoryTrainingStore store = new InMemoryTrainingStore();
    private final ITrainerGateway trainer = mock(ITrainerGateway.class);
    private final IHuggingFaceRepos huggingFace = mock(IHuggingFaceRepos.class);
    private final IApiTokens tokens = mock(IApiTokens.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final Messages messages = mock(Messages.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final WeightsFile weights = mock(WeightsFile.class);
    private TrainingHfUploadService service;

    @BeforeEach
    void setUp() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
        when(tokens.resolve(TOKEN_ID, "HUGGINGFACE")).thenReturn("hf_secret");
        when(huggingFace.whoami("hf_secret")).thenReturn(new HfAccount("sandro", "write"));
        when(trainer.weights("train-1")).thenReturn(Optional.of(weights));
        service = new TrainingHfUploadService(store, trainer, huggingFace, tokens, systemEvents, messages, publisher);
    }

    private Training training(TrainingStatus status, boolean hfCopy, HfStatus hfStatus) {
        TrainingDataset snapshot = new TrainingDataset("Gatto", "TOK", LoraType.SUBJECT, null, true, 1L, NOW);
        ReflectionTestUtils.setField(snapshot, "id", 99L);
        snapshot.applyLaunchSettings(new LaunchSettings(null, 1000, null, hfCopy, hfCopy ? TOKEN_ID : null, null, true), NOW);
        Training training = new Training(snapshot, "train-1", TrainingStatus.PROCESSING, "v", "acct", "gatto", hfCopy ? REPO : null, NOW);
        if (status == TrainingStatus.SUCCEEDED) {
            training.succeed("ok", 10.0, NOW);
        }
        training.setHfStatus(hfStatus);
        return store.save(training);
    }

    // --- chi si puo' caricare ------------------------------------------------------------------------------------

    @Test
    void onlyASucceededTrainingWhoseCopyIsMissingOrUnverifiableCanBeUploaded() {
        assertThat(service.canUpload(training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND))).isTrue();
        assertThat(service.canUpload(training(TrainingStatus.SUCCEEDED, true, HfStatus.UNVERIFIED))).isTrue();
        assertThat(service.canUpload(training(TrainingStatus.SUCCEEDED, true, HfStatus.VERIFIED))).isFalse();
        assertThat(service.canUpload(training(TrainingStatus.SUCCEEDED, true, HfStatus.PENDING))).as("ancora in tolleranza: il push potrebbe arrivare").isFalse();
        assertThat(service.canUpload(training(TrainingStatus.SUCCEEDED, false, HfStatus.NONE))).isFalse();
        assertThat(service.canUpload(training(TrainingStatus.FAILED, true, HfStatus.NOT_FOUND))).isFalse();
        assertThat(service.canUpload(training(TrainingStatus.PROCESSING, true, HfStatus.NOT_FOUND))).isFalse();
    }

    // --- la richiesta --------------------------------------------------------------------------------------------

    @Test
    void aRequestChecksTheTokenThenStartsTheBackgroundUploadAndReturns() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);

        service.request(training.getId(), TOKEN_ID);

        verify(publisher).publishEvent(new HfUploadRequestedEvent(training.getId(), TOKEN_ID));
        verify(publisher).publishEvent(new TrainingChangedEvent(training.getId()));
        assertThat(service.isUploading(training.getId())).isTrue();
        assertThat(service.canUpload(training)).as("in corso: niente secondo avvio").isFalse();
        verifyNoInteractions(trainer); // il lavoro vero e' del thread in background
        verify(huggingFace, never()).uploadWeights(anyString(), anyString(), any(), anyString());
    }

    @Test
    void aSecondRequestWhileOneIsRunningIsRejected() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        service.request(training.getId(), TOKEN_ID);

        assertThatThrownBy(() -> service.request(training.getId(), TOKEN_ID)).isInstanceOfSatisfying(TrainingException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("training.hfUpload.running");
            assertThat(e.isReportable()).isFalse();
        });
        verify(publisher).publishEvent(any(HfUploadRequestedEvent.class)); // una volta sola
    }

    @Test
    void aTrainingThatCannotBeUploadedOrAnUnknownOneOrAMissingTokenIsAPlainRejection() {
        Training verified = training(TrainingStatus.SUCCEEDED, true, HfStatus.VERIFIED);
        Training ok = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);

        assertThatThrownBy(() -> service.request(verified.getId(), TOKEN_ID)).hasMessage("training.hfUpload.notAllowed");
        assertThatThrownBy(() -> service.request(999L, TOKEN_ID)).hasMessage("training.error.trainingNotFound");
        assertThatThrownBy(() -> service.request(ok.getId(), null)).hasMessage("training.hfUpload.tokenRequired");

        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(service.isUploading(ok.getId())).isFalse();
    }

    @Test
    void aReadOnlyTokenIsRejectedBeforeAnythingIsDownloaded() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        when(huggingFace.whoami("hf_secret")).thenReturn(new HfAccount("sandro", "read"));

        assertThatThrownBy(() -> service.request(training.getId(), TOKEN_ID)).hasMessage("training.error.hfReadOnly");

        assertThat(service.isUploading(training.getId())).isFalse();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void anExpiredOrDeletedTokenIsAnExpectedRejectionAndLeavesTheButtonAvailable() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.UNVERIFIED);
        when(tokens.resolve(TOKEN_ID, "HUGGINGFACE")).thenThrow(new TokenException("scaduto"));

        assertThatThrownBy(() -> service.request(training.getId(), TOKEN_ID)).isInstanceOf(TokenException.class);

        assertThat(service.canUpload(training)).isTrue();
    }

    /** L'executor rifiuta il lavoro (coda piena): nessuno lo fara', il bottone non deve restare bloccato per sempre. */
    @Test
    void aRejectedBackgroundJobDoesNotLeaveTheUploadMarkedAsRunning() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        doThrow(new TaskRejectedException("coda piena")).when(publisher).publishEvent(any(HfUploadRequestedEvent.class));

        assertThatThrownBy(() -> service.request(training.getId(), TOKEN_ID)).isInstanceOf(TaskRejectedException.class);

        assertThat(service.isUploading(training.getId())).isFalse();
        assertThat(service.canUpload(training)).isTrue();
    }

    // --- il lavoro in background ---------------------------------------------------------------------------------

    @Test
    void aSuccessfulUploadMarksTheCopyAsVerifiedAndFreesTheTraining() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        service.request(training.getId(), TOKEN_ID);

        service.upload(training.getId(), TOKEN_ID);

        verify(huggingFace).uploadWeights("hf_secret", REPO, weights, "training.hfUpload.commit");
        assertThat(store.rows.get(training.getId()).getHfStatus()).isEqualTo(HfStatus.VERIFIED);
        assertThat(service.isUploading(training.getId())).isFalse();
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(new TrainingChangedEvent(training.getId())); // all'avvio e alla fine
        verify(systemEvents, never()).record(anyString(), any(Throwable.class), anyString());
    }

    @Test
    void aFailedUploadLeavesTheRowAsItWasRecordsTheErrorAndLetsTheUserRetry() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        service.request(training.getId(), TOKEN_ID);
        HuggingFaceException failure = new HuggingFaceException("503", null, Kind.TRANSIENT);
        doThrow(failure).when(huggingFace).uploadWeights(anyString(), anyString(), any(), anyString());

        service.upload(training.getId(), TOKEN_ID);

        assertThat(store.rows.get(training.getId()).getHfStatus()).isEqualTo(HfStatus.NOT_FOUND);
        verify(systemEvents).record(eq("uploadTrainingWeights"), eq(failure), eq("training:" + training.getId()));
        assertThat(service.isUploading(training.getId())).isFalse();
        assertThat(service.canUpload(training)).isTrue();
    }

    @Test
    void anExpectedRefusalIsAWarningNotAnError() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        service.request(training.getId(), TOKEN_ID);
        doThrow(new HuggingFaceException("repo non scrivibile")).when(huggingFace).uploadWeights(anyString(), anyString(), any(), anyString());

        service.upload(training.getId(), TOKEN_ID);

        verify(systemEvents).warn(eq(AppEventSource.TRAINING), eq("uploadTrainingWeights"), eq("training:" + training.getId()), eq("repo non scrivibile"));
        verify(systemEvents, never()).record(anyString(), any(Throwable.class), anyString());
    }

    @Test
    void aTrainingWithoutAWeightsFileWarnsAndUploadsNothing() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        service.request(training.getId(), TOKEN_ID);
        when(trainer.weights("train-1")).thenReturn(Optional.empty());

        service.upload(training.getId(), TOKEN_ID);

        verify(huggingFace, never()).uploadWeights(anyString(), anyString(), any(), anyString());
        verify(systemEvents).warn(eq(AppEventSource.TRAINING), eq("uploadTrainingWeights"), anyString(), eq("training.hfUpload.noWeights"));
        assertThat(store.rows.get(training.getId()).getHfStatus()).isEqualTo(HfStatus.NOT_FOUND);
        assertThat(service.isUploading(training.getId())).isFalse();
    }

    @Test
    void anUnexpectedFailureIsRecordedAndStillFreesTheTraining() {
        Training training = training(TrainingStatus.SUCCEEDED, true, HfStatus.NOT_FOUND);
        service.request(training.getId(), TOKEN_ID);
        when(trainer.weights("train-1")).thenThrow(new IllegalStateException("bug"));

        service.upload(training.getId(), TOKEN_ID);

        verify(systemEvents).record(eq("uploadTrainingWeights"), any(IllegalStateException.class), anyString());
        assertThat(service.isUploading(training.getId())).isFalse();
    }

    @Test
    void aTrainingDeletedMeanwhileEndsQuietly() {
        service.upload(404L, TOKEN_ID);

        verifyNoInteractions(huggingFace, trainer);
        verify(systemEvents, never()).record(anyString(), any(Throwable.class), anyString());
    }
}
