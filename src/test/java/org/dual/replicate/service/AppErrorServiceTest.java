package org.dual.replicate.service;

import java.util.List;

import org.dual.replicate.domain.AppError;
import org.dual.replicate.domain.AppErrorSource;
import org.dual.replicate.domain.event.ErrorToastEvent;
import org.dual.replicate.repository.AppErrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contro il DB in-memory di test (REQUIRES_NEW committa davvero: nessun @Transactional sul test,
 * quindi si ripulisce a mano).
 */
@SpringBootTest
@org.springframework.test.context.event.RecordApplicationEvents
class AppErrorServiceTest {

    @Autowired
    private AppErrorService service;

    @Autowired
    private AppErrorRepository repository;

    @Autowired
    private org.springframework.test.context.event.ApplicationEvents events;

    private List<ErrorToastEvent> toasts() {
        return events.stream(ErrorToastEvent.class).toList();
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void recordPersistsARowAndPublishesOneToast() {
        AppErrorService.Recorded recorded = service.record(AppErrorSource.REPLICATE, "createPrediction",
                new IllegalStateException("rete giu'"), 5L, 7L);

        List<AppError> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        AppError row = rows.get(0);
        assertThat(row.getSource()).isEqualTo(AppErrorSource.REPLICATE);
        assertThat(row.getOperation()).isEqualTo("createPrediction");
        assertThat(row.getErrorType()).isEqualTo("IllegalStateException");
        assertThat(row.getMessage()).isEqualTo("rete giu'");
        assertThat(row.getDetails()).contains("IllegalStateException");
        assertThat(row.getGenerationId()).isEqualTo(5L);
        assertThat(row.getConversationId()).isEqualTo(7L);
        assertThat(row.getOccurrences()).isEqualTo(1);
        assertThat(recorded.firstOfSeries()).isTrue();
        assertThat(toasts()).hasSize(1);
        assertThat(toasts().get(0).message()).contains("rete giu'");
    }

    /** Un'outage con polling ogni 2s non deve produrre una riga/un toast per poll. */
    @Test
    void repeatedIdenticalErrorsAreGroupedIntoOneSeriesWithASingleToast() {
        for (int i = 0; i < 5; i++) {
            service.record(AppErrorSource.REPLICATE, "getPrediction", new RuntimeException("timeout " + i), 5L, null);
        }

        List<AppError> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOccurrences()).isEqualTo(5);
        assertThat(rows.get(0).getMessage()).isEqualTo("timeout 4");
        assertThat(toasts()).hasSize(1);
    }

    @Test
    void differentGenerationsOrOperationsAreSeparateSeries() {
        service.record(AppErrorSource.REPLICATE, "getPrediction", new RuntimeException("x"), 1L, null);
        service.record(AppErrorSource.REPLICATE, "getPrediction", new RuntimeException("x"), 2L, null);
        service.record(AppErrorSource.REPLICATE, "cancelPrediction", new RuntimeException("x"), 1L, null);

        assertThat(repository.findAll()).hasSize(3);
        assertThat(toasts()).hasSize(3);
    }

    /** Il registro non deve mai far fallire il chiamante, nemmeno con un'eccezione senza messaggio o null. */
    @Test
    void recordNeverThrowsAndHandlesNullMessageAndNullError() {
        service.record(AppErrorSource.INTERNAL, "op", new RuntimeException());
        service.record(AppErrorSource.INTERNAL, "op2", null);

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void longMessagesAndStacksAreTruncated() {
        service.record(AppErrorSource.OPENROUTER, "chatTurn", new RuntimeException("x".repeat(5000)));

        AppError row = repository.findAll().get(0);
        assertThat(row.getMessage().length()).isLessThanOrEqualTo(501);
        assertThat(row.getDetails().length()).isLessThanOrEqualTo(8002);
    }

    @Test
    void theSourceIsInferredFromTheRemoteExceptionEvenWhenWrapped() {
        var storage = new org.dual.replicate.service.storage.StorageException("webdav giu'", null,
                org.dual.replicate.remote.RemoteServiceException.Kind.TRANSIENT);

        service.record("serveImage", new RuntimeException("incapsulata", storage));
        service.record("qualcosa", new IllegalStateException("bug"));

        assertThat(repository.findAll()).extracting(AppError::getSource)
                .containsExactlyInAnyOrder(AppErrorSource.STORAGE, AppErrorSource.INTERNAL);
    }

    @Test
    void aTransientFailureIsFlaggedInTheToastPayload() {
        var transientFailure = new org.dual.replicate.service.storage.StorageException("giu'", null,
                org.dual.replicate.remote.RemoteServiceException.Kind.TRANSIENT);
        var permanent = new org.dual.replicate.service.storage.StorageException("403", null,
                org.dual.replicate.remote.RemoteServiceException.Kind.PERMANENT);

        assertThat(service.record("a", transientFailure).transientFailure()).isTrue();
        assertThat(service.record("b", permanent).transientFailure()).isFalse();
        assertThat(toasts()).extracting(ErrorToastEvent::transientFailure).containsExactly(true, false);
    }

    /** L'header HX-Trigger e' uno solo: aggiungere il toast a un evento gia' presente non lo sovrascrive. */
    @Test
    void addingAToastKeepsAnExistingHxTriggerEvent() {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        response.setHeader("HX-Trigger", "gallery-update");

        service.recordForHtmx(response, "op", new IllegalStateException("bug"));

        String header = response.getHeader("HX-Trigger");
        assertThat(header).startsWith("{").contains("\"gallery-update\"").contains("\"app-error\"")
                .contains("\"message\"").contains("\"transient\":false");
    }

    @Test
    void addingAnEventToAJsonHxTriggerMergesBothAndKeepsDetails() {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        response.setHeader("HX-Trigger", "{\"showMessage\":\"ciao\"}");

        service.addHxTrigger(response, "gallery-update", "");

        assertThat(response.getHeader("HX-Trigger")).contains("\"showMessage\":\"ciao\"").contains("\"gallery-update\"");
    }
}
