package org.dual.replicate.app.training.application;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.dual.replicate.app.generation.domain.ApiTokenProvider;
import org.dual.replicate.app.shared.domain.AppEventSource;
import org.dual.replicate.app.shared.domain.AppEventSubjects;
import org.dual.replicate.app.training.domain.HfAccount;
import org.dual.replicate.app.training.domain.HfStatus;
import org.dual.replicate.app.training.domain.Training;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.domain.TrainingStatus;
import org.dual.replicate.app.training.domain.WeightsFile;
import org.dual.replicate.app.training.domain.event.HfUploadRequestedEvent;
import org.dual.replicate.app.training.domain.event.TrainingChangedEvent;
import org.dual.replicate.app.training.port.in.ITrainingHfUploads;
import org.dual.replicate.app.training.port.out.IHuggingFaceRepos;
import org.dual.replicate.app.training.port.out.ITrainerGateway;
import org.dual.replicate.app.training.port.out.ITrainingStore;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Il caricamento a mano dei pesi su HuggingFace, per i training la cui copia non c'e' (il push del trainer non e' avvenuto) o non si e' potuta verificare.
 *
 * <p><b>Due metodi, due momenti.</b> {@link #request} gira nella richiesta web: controlla tutto cio' che si puo' sapere a basso costo (stato del training, token
 * esistente e non scaduto, permesso di scrittura) e ritorna, cosi' un rifiuto e' un toast subito. {@link #upload} gira su un thread a parte (centinaia di MB:
 * oltre il timeout di htmx).
 *
 * <p><b>Nessuno stato su DB</b> per il "in corso": un insieme in memoria (una sola istanza dell'app) impedisce il doppio avvio dello stesso training; un riavvio a
 * meta' lo azzera, il training resta NOT_FOUND/UNVERIFIED e il bottone ricompare. Un carico interrotto non lascia nulla di rotto (l'oggetto LFS non e' nel repo
 * finche' non c'e' il commit), e un secondo tentativo salta il trasferimento se HuggingFace ha gia' il contenuto.
 *
 * <p>Il token in chiaro vive solo dentro questi metodi: nell'evento viaggia l'ID. Un esito di successo e' {@code VERIFIED} sulla riga (il commit riuscito e' la
 * prova: nessun secondo controllo dell'elenco dei file); un fallimento lascia lo stato com'era e finisce negli eventi di sistema.
 */
@Service
public class TrainingHfUploadService implements ITrainingHfUploads {

    private static final Set<HfStatus> UPLOADABLE = Set.of(HfStatus.NOT_FOUND, HfStatus.UNVERIFIED);
    private static final String OPERATION = "uploadTrainingWeights";

    private final ITrainingStore store;
    private final ITrainerGateway trainer;
    private final IHuggingFaceRepos huggingFace;
    private final IApiTokens tokens;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final ApplicationEventPublisher eventPublisher;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public TrainingHfUploadService(ITrainingStore store, ITrainerGateway trainer, IHuggingFaceRepos huggingFace, IApiTokens tokens, ISystemEvents systemEvents,
                                   Messages messages, ApplicationEventPublisher eventPublisher) {
        this.store = store;
        this.trainer = trainer;
        this.huggingFace = huggingFace;
        this.tokens = tokens;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public boolean canUpload(Training training) {
        return isEligible(training) && !inFlight.contains(training.getId());
    }

    @Override
    public boolean isUploading(Long trainingId) {
        return trainingId != null && inFlight.contains(trainingId);
    }

    private static boolean isEligible(Training training) {
        return training.getStatus() == TrainingStatus.SUCCEEDED && training.isHfPublish() && training.getHfRepoId() != null
                && UPLOADABLE.contains(training.getHfStatus());
    }

    @Override
    public void request(Long trainingId, Long tokenId) {
        Training training = store.findById(trainingId).orElseThrow(() -> new TrainingException(messages.get("training.error.trainingNotFound")));
        if (inFlight.contains(trainingId)) {
            throw new TrainingException(messages.get("training.hfUpload.running"));
        }
        if (!isEligible(training)) {
            throw new TrainingException(messages.get("training.hfUpload.notAllowed"));
        }
        if (tokenId == null) {
            throw new TrainingException(messages.get("training.hfUpload.tokenRequired"));
        }
        // Un token scaduto, cancellato o di un altro provider e' un rifiuto (TokenException); uno di sola lettura non puo' scrivere: si dice PRIMA di scaricare 170 MB.
        String token = tokens.resolve(tokenId, ApiTokenProvider.HUGGINGFACE.name());
        HfAccount account = huggingFace.whoami(token);
        if (account.isReadOnly()) {
            throw new TrainingException(messages.get("training.error.hfReadOnly"));
        }
        if (!inFlight.add(trainingId)) {
            throw new TrainingException(messages.get("training.hfUpload.running"));
        }
        try {
            eventPublisher.publishEvent(new HfUploadRequestedEvent(trainingId, tokenId));
        } catch (RuntimeException e) {
            inFlight.remove(trainingId); // l'executor ha rifiutato il lavoro: nessuno lo fara', il bottone non deve restare bloccato
            throw e;
        }
        eventPublisher.publishEvent(new TrainingChangedEvent(trainingId));
    }

    @Override
    public void upload(Long trainingId, Long tokenId) {
        String subject = AppEventSubjects.ofTraining(trainingId);
        try {
            Optional<Training> found = store.findById(trainingId);
            if (found.isEmpty()) {
                return; // eliminato nel frattempo
            }
            Training training = found.get();
            String token = tokens.resolve(tokenId, ApiTokenProvider.HUGGINGFACE.name());
            Optional<WeightsFile> weights = trainer.weights(training.getExternalId());
            if (weights.isEmpty()) {
                systemEvents.warn(AppEventSource.TRAINING, OPERATION, subject, messages.get("training.hfUpload.noWeights", trainingId));
                return;
            }
            huggingFace.uploadWeights(token, training.getHfRepoId(), weights.get(), messages.get("training.hfUpload.commit", trainingId));
            markVerified(trainingId);
        } catch (RemoteServiceException e) {
            if (e.isReportable()) {
                systemEvents.record(OPERATION, e, subject);
            } else {
                systemEvents.warn(AppEventSource.TRAINING, OPERATION, subject, e.getMessage());
            }
        } catch (RuntimeException e) {
            systemEvents.record(OPERATION, e, subject);
        } finally {
            inFlight.remove(trainingId);
            eventPublisher.publishEvent(new TrainingChangedEvent(trainingId));
        }
    }

    /** Ricarica la riga sotto il lock dei training: nel frattempo un poll o l'eliminazione possono averla cambiata. */
    private void markVerified(Long trainingId) {
        synchronized (TrainingLocks.of(trainingId)) {
            store.findById(trainingId).ifPresent(training -> {
                training.setHfStatus(HfStatus.VERIFIED);
                store.save(training);
            });
        }
    }
}
