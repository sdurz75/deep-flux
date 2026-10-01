package org.dual.replicate.core.events.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.dual.replicate.app.AppEventSource;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.domain.SystemEvent;
import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.port.out.ISystemEventStore;
import org.dual.replicate.core.push.port.in.IClientPushStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contro il DB in-memory di test (REQUIRES_NEW committa davvero: nessun @Transactional sul test,
 * quindi si ripulisce a mano).
 */
@SpringBootTest
class SystemEventServiceTest {

    @Autowired
    private ISystemEvents service;

    @Autowired
    private ISystemEventStore repository;

    @Autowired
    private IClientPushStream pushStream;

    /** I toast arrivano alle tab come evento SSE "system-event": ci si abbona come farebbe una tab. */
    private final List<Map<String, Object>> toasts = new CopyOnWriteArrayList<>();
    private Disposable subscription;

    private List<Map<String, Object>> toasts() {
        return toasts;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void clean() {
        repository.deleteAll();
        toasts.clear();
        subscription = pushStream.subscribe()
                .filter(m -> "system-event".equals(m.event()))
                .subscribe(m -> toasts.add((Map<String, Object>) m.data()));
    }

    @AfterEach
    void unsubscribe() {
        subscription.dispose();
    }

    @Test
    void recordPersistsARowAndPublishesOneToast() {
        ISystemEvents.Recorded recorded = service.record(AppEventSource.REPLICATE, "createPrediction",
                new IllegalStateException("rete giu'"), "generation:5");

        List<SystemEvent> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        SystemEvent row = rows.get(0);
        assertThat(row.getSource()).isEqualTo(AppEventSource.REPLICATE.name());
        assertThat(row.getOperation()).isEqualTo("createPrediction");
        assertThat(row.getErrorType()).isEqualTo("IllegalStateException");
        assertThat(row.getMessage()).isEqualTo("rete giu'");
        assertThat(row.getDetails()).contains("IllegalStateException");
        assertThat(row.getSubject()).isEqualTo("generation:5");
        assertThat(row.getOccurrences()).isEqualTo(1);
        assertThat(recorded.firstOfSeries()).isTrue();
        assertThat(toasts()).hasSize(1);
        assertThat((String) toasts().get(0).get("message")).contains("rete giu'");
    }

    /** Un'outage con polling ogni 2s non deve produrre una riga/un toast per poll. */
    @Test
    void repeatedIdenticalErrorsAreGroupedIntoOneSeriesWithASingleToast() {
        for (int i = 0; i < 5; i++) {
            service.record(AppEventSource.REPLICATE, "getPrediction", new RuntimeException("timeout " + i), "generation:5");
        }

        List<SystemEvent> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOccurrences()).isEqualTo(5);
        assertThat(rows.get(0).getMessage()).isEqualTo("timeout 4");
        assertThat(toasts()).hasSize(1);
    }

    @Test
    void differentGenerationsOrOperationsAreSeparateSeries() {
        service.record(AppEventSource.REPLICATE, "getPrediction", new RuntimeException("x"), "generation:1");
        service.record(AppEventSource.REPLICATE, "getPrediction", new RuntimeException("x"), "generation:2");
        service.record(AppEventSource.REPLICATE, "cancelPrediction", new RuntimeException("x"), "generation:1");

        assertThat(repository.findAll()).hasSize(3);
        assertThat(toasts()).hasSize(3);
    }

    /** Il registro non deve mai far fallire il chiamante, nemmeno con un'eccezione senza messaggio o null. */
    @Test
    void recordNeverThrowsAndHandlesNullMessageAndNullError() {
        service.record(CoreEventSource.INTERNAL, "op", new RuntimeException());
        service.record(CoreEventSource.INTERNAL, "op2", null);

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void longMessagesAndStacksAreTruncated() {
        service.record(AppEventSource.OPENROUTER, "chatTurn", new RuntimeException("x".repeat(5000)));

        SystemEvent row = repository.findAll().get(0);
        assertThat(row.getMessage().length()).isLessThanOrEqualTo(501);
        assertThat(row.getDetails().length()).isLessThanOrEqualTo(8002);
    }

    @Test
    void theSourceIsInferredFromTheRemoteExceptionEvenWhenWrapped() {
        var storage = new org.dual.replicate.core.storage.domain.StorageException("webdav giu'", null,
                org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind.TRANSIENT);

        service.record("serveImage", new RuntimeException("incapsulata", storage));
        service.record("qualcosa", new IllegalStateException("bug"));

        assertThat(repository.findAll()).extracting(SystemEvent::getSource)
                .containsExactlyInAnyOrder(CoreEventSource.STORAGE.name(), CoreEventSource.INTERNAL.name());
    }

    @Test
    void aTransientFailureIsFlaggedInTheToastPayload() {
        var transientFailure = new org.dual.replicate.core.storage.domain.StorageException("giu'", null,
                org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind.TRANSIENT);
        var permanent = new org.dual.replicate.core.storage.domain.StorageException("403", null,
                org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind.PERMANENT);

        assertThat(service.record("a", transientFailure).transientFailure()).isTrue();
        assertThat(service.record("b", permanent).transientFailure()).isFalse();
        assertThat(toasts()).extracting(t -> t.get("transient")).containsExactly(true, false);
    }

    /** Un errore senza generazione ne' subject (predicati null-safe della query di serie) raggruppa comunque in una sola riga. */
    @Test
    void errorsWithoutGenerationOrSubjectStillFormOneSeries() {
        service.record(CoreEventSource.INTERNAL, "op", new RuntimeException("a"));
        service.record(CoreEventSource.INTERNAL, "op", new RuntimeException("b"));

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().get(0).getSeverity()).isEqualTo(SystemEventSeverity.ERROR);
        assertThat(toasts()).hasSize(1);
    }

    @Test
    void warnPersistsAWarningRowAndPublishesOneWarningToast() {
        ISystemEvents.Recorded recorded = service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:12", "Il token scade tra 3 giorni");

        SystemEvent row = repository.findAll().get(0);
        assertThat(row.getSeverity()).isEqualTo(SystemEventSeverity.WARNING);
        assertThat(row.getSubject()).isEqualTo("token:12");
        assertThat(row.getDetails()).isNull();
        assertThat(recorded.severity()).isEqualTo(SystemEventSeverity.WARNING);
        assertThat(recorded.firstOfSeries()).isTrue();
        assertThat(toasts()).hasSize(1);
        assertThat(toasts().get(0).get("severity")).isEqualTo("WARNING");
        assertThat((String) toasts().get(0).get("message")).contains("scade tra 3 giorni");
    }

    @Test
    void warningsOfTheSameSubjectGroupAndDifferentSubjectsAreSeparateSeries() {
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:1", "scade");
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:1", "scade ancora");
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:2", "scade");

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findAll()).extracting(SystemEvent::getOccurrences).containsExactlyInAnyOrder(2, 1);
        assertThat(toasts()).hasSize(2);
    }

    @Test
    void anErrorAndAWarningWithTheSameKeysAreNotTheSameSeries() {
        service.warn(CoreEventSource.INTERNAL, "op", null, "x");
        service.record(CoreEventSource.INTERNAL, "op", new RuntimeException("Warning"));

        assertThat(repository.findAll()).hasSize(2);
    }

    /** Campanella: nuovi eventi non letti, "segna come letti", e una ripetizione di una serie gia' letta resta letta. */
    @Test
    void unseenEventsFeedTheBellAndAcknowledgementSticksAcrossRepeats() {
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:1", "a");
        service.record(AppEventSource.REPLICATE, "createPrediction", new RuntimeException("x"), "generation:9");

        ISystemEvents.Unseen unseen = service.unseen();
        assertThat(unseen.count()).isEqualTo(2);
        assertThat(unseen.hasError()).isTrue();
        assertThat(unseen.latest()).hasSize(2);

        service.markAllSeen();
        assertThat(service.unseen().count()).isZero();

        // stessa serie, ancora entro la finestra: il contatore sale ma NON torna "non letta"
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:1", "a di nuovo");
        assertThat(service.unseen().count()).isZero();
        // una serie nuova (altro subject) si
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:2", "b");
        ISystemEvents.Unseen again = service.unseen();
        assertThat(again.count()).isEqualTo(1);
        assertThat(again.hasError()).isFalse();
    }

    @Test
    void markSeenAcknowledgesOneEventAndMarkSeenBySubjectClearsATokensWarnings() {
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:1", "a");
        service.warn(CoreEventSource.TOKENS, "tokenExpired", "token:1", "b");
        service.warn(CoreEventSource.TOKENS, "tokenExpiring", "token:2", "c");
        Long first = repository.findAll().get(0).getId();

        service.markSeen(first);
        assertThat(service.unseen().count()).isEqualTo(2);

        service.markSeenBySubject("token:1");
        assertThat(service.unseen().count()).isEqualTo(1);
        assertThat(service.unseen().latest().get(0).getSubject()).isEqualTo("token:2");
    }
}
