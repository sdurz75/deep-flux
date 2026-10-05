package org.dual.replicate.app.training.adapter.in.scheduling;

import org.dual.replicate.app.AppStartupOrder;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.port.in.ICaptionJobs;
import org.dual.replicate.app.training.port.in.ITrainingResults;
import org.dual.replicate.app.training.port.in.ITrainings;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Rete di sicurezza dei lavori di addestramento. Nessuna didascalia resta "in corso" per sempre (riavvio a meta' lavoro, evento perso, coda piena):
 * all'avvio ogni didascalia in sospeso si rilancia (i lavori in corso sono spariti col processo); poi, a intervalli, di nuovo: il lavoro e' idempotente e
 * ricorda quali immagini ha in corso, quindi un rilancio non ne duplica una gia' partita.
 *
 * <p>E poi i TRAINING: un training dura decine di minuti e la pagina che lo guarda puo' essere chiusa, quindi nessuno lo interrogherebbe. Qui si fa avanzare ogni
 * training non terminale all'avvio e ogni {@code app.training.poll-interval} (il timeout di business lo applica {@code ITrainings#refresh}). Disattivabile con
 * {@code app.recovery.enabled=false} (i test e il profilo {@code backup}, che non devono avviare nulla di nascosto). Un training RIUSCITO ha ancora un risultato da
 * completare (preset, modello utilizzabile, copia su HuggingFace): se il lavoro in background e' andato perso, lo riprende qui.
 */
@Service
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class TrainingRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TrainingRecoveryService.class);

    private final ICaptionJobs captionJobs;
    private final ITrainings trainings;
    private final ITrainingResults results;
    private final ISystemEvents systemEvents;

    public TrainingRecoveryService(ICaptionJobs captionJobs, ITrainings trainings, ITrainingResults results, ISystemEvents systemEvents) {
        this.captionJobs = captionJobs;
        this.trainings = trainings;
        this.results = results;
        this.systemEvents = systemEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(AppStartupOrder.TRAINING_RECOVERY)
    public void recoverOnStartup() {
        recoverCaptions();
        pollTrainings();
        recoverResults();
    }

    /** I risultati rimasti a meta' (preset non creato, modello non ancora censibile, copia su HuggingFace da verificare) dei training finiti di recente. */
    @Scheduled(fixedDelayString = "${app.training.result-sweep-interval:5m}", initialDelayString = "${app.training.result-sweep-interval:5m}")
    public void sweepResults() {
        recoverResults();
    }

    @Scheduled(fixedDelayString = "${app.training.poll-interval:30s}", initialDelayString = "${app.training.poll-interval:30s}")
    public void pollInProgressTrainings() {
        pollTrainings();
    }

    @Scheduled(fixedDelayString = "${app.training.caption-sweep-interval:5m}", initialDelayString = "${app.training.caption-sweep-interval:5m}")
    public void sweep() {
        recoverCaptions();
    }

    private void recoverCaptions() {
        try {
            int restarted = captionJobs.recoverPending();
            if (restarted > 0) {
                log.info("Recupero: {} didascalie di addestramento rilanciate", restarted);
            }
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "recoverTrainingCaptions", e);
        }
    }

    /**
     * Interroga Replicate per ogni training non terminale. Uno che fallisce non ferma gli altri; {@code refresh} registra gia' da se' i guasti del servizio
     * remoto, qui restano solo quelli interni (database).
     */
    private void pollTrainings() {
        try {
            for (Training training : trainings.inProgress()) {
                try {
                    trainings.refresh(training.getId());
                } catch (RuntimeException e) {
                    // Un rifiuto atteso (il training e' stato eliminato mentre si scorreva l'elenco) non e' un guasto.
                    if (!(e instanceof RemoteServiceException remote) || remote.isReportable()) {
                        systemEvents.record(AppEventSource.TRAINING, "pollTraining", e, AppEventSubjects.ofTraining(training.getId()));
                    }
                }
            }
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "pollTrainings", e);
        }
    }

    private void recoverResults() {
        try {
            int resumed = results.recoverPending();
            if (resumed > 0) {
                log.info("Recupero: {} risultati di training ripresi", resumed);
            }
        } catch (RuntimeException e) {
            systemEvents.record(CoreEventSource.INTERNAL, "recoverTrainingResults", e);
        }
    }
}
