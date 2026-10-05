package org.dual.replicate.app.training.adapter.in.scheduling;

import java.time.Instant;
import java.util.List;

import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.training.domain.LoraType;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingDataset;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.port.in.ICaptionJobs;
import org.dual.replicate.app.training.port.in.ITrainingResults;
import org.dual.replicate.app.training.port.in.ITrainings;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Il poller dei training in corso e il recupero all'avvio, con i collaboratori finti: nessuna rete. */
class TrainingRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final ICaptionJobs captionJobs = mock(ICaptionJobs.class);
    private final ITrainings trainings = mock(ITrainings.class);
    private final ITrainingResults results = mock(ITrainingResults.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final TrainingRecoveryService service = new TrainingRecoveryService(captionJobs, trainings, results, systemEvents);

    private static Training training(long id) {
        TrainingDataset snapshot = new TrainingDataset("n", "TOK", LoraType.SUBJECT, null, true, null, NOW);
        ReflectionTestUtils.setField(snapshot, "id", 100 + id);
        Training training = new Training(snapshot, "t-" + id, TrainingStatus.PROCESSING, "v", "acct", "m" + id, null, NOW);
        ReflectionTestUtils.setField(training, "id", id);
        return training;
    }

    @Test
    void thePollRefreshesEveryTrainingInProgress() {
        when(trainings.inProgress()).thenReturn(List.of(training(1), training(2)));

        service.pollInProgressTrainings();

        verify(trainings).refresh(1L);
        verify(trainings).refresh(2L);
    }

    @Test
    void aFailingTrainingDoesNotStopTheOthersAndTheFailureIsRecordedWithItsSubject() {
        when(trainings.inProgress()).thenReturn(List.of(training(1), training(2)));
        RuntimeException broken = new IllegalStateException("db");
        doThrow(broken).when(trainings).refresh(1L);

        service.pollInProgressTrainings();

        verify(trainings).refresh(2L);
        verify(systemEvents).record(eq(AppEventSource.TRAINING), eq("pollTraining"), eq(broken), eq("training:1"));
    }

    @Test
    void aReportableServiceFailureIsRecordedButAnExpectedRefusalIsNot() {
        when(trainings.inProgress()).thenReturn(List.of(training(1), training(2)));
        ReplicateException down = new ReplicateException("giu'", null, Kind.PERMANENT);
        doThrow(down).when(trainings).refresh(1L);
        doThrow(new TrainingException("eliminato nel frattempo")).when(trainings).refresh(2L); // rifiuto atteso (REJECTED)

        service.pollInProgressTrainings();

        verify(systemEvents).record(eq(AppEventSource.TRAINING), eq("pollTraining"), eq(down), eq("training:1"));
        verify(systemEvents, never()).record(any(), anyString(), any(TrainingException.class), eq("training:2"));
    }

    @Test
    void ifTheListCannotBeReadTheFailureIsRecordedAsInternal() {
        RuntimeException broken = new IllegalStateException("db");
        when(trainings.inProgress()).thenThrow(broken);

        service.pollInProgressTrainings();

        verify(systemEvents).record(CoreEventSource.INTERNAL, "pollTrainings", broken);
        verify(trainings, never()).refresh(anyLong());
    }

    @Test
    void theStartupRestartsTheCaptionsAndPollsTheTrainings() {
        when(captionJobs.recoverPending()).thenReturn(2);
        when(trainings.inProgress()).thenReturn(List.of(training(1)));

        service.recoverOnStartup();

        verify(captionJobs).recoverPending();
        verify(trainings).refresh(1L);
    }

    @Test
    void theStartupAndTheSweepResumeTheIncompleteResults() {
        when(results.recoverPending()).thenReturn(1);

        service.recoverOnStartup();
        service.sweepResults();

        verify(results, org.mockito.Mockito.times(2)).recoverPending();
    }

    @Test
    void aFailureResumingResultsIsRecordedAsInternalAndDoesNotBreakTheStartup() {
        RuntimeException broken = new IllegalStateException("db");
        when(results.recoverPending()).thenThrow(broken);

        service.recoverOnStartup();

        verify(systemEvents).record(CoreEventSource.INTERNAL, "recoverTrainingResults", broken);
    }

    @Test
    void aFailureRestartingCaptionsDoesNotPreventPollingTheTrainings() {
        RuntimeException broken = new IllegalStateException("coda");
        when(captionJobs.recoverPending()).thenThrow(broken);
        when(trainings.inProgress()).thenReturn(List.of(training(1)));

        service.recoverOnStartup();

        verify(systemEvents).record(CoreEventSource.INTERNAL, "recoverTrainingCaptions", broken);
        verify(trainings).refresh(1L);
    }
}
