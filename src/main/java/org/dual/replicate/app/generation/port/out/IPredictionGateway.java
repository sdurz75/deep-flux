package org.dual.replicate.app.generation.port.out;

import java.util.Map;

import org.dual.replicate.app.generation.domain.Prediction;

/** Il servizio che esegue le generazioni (oggi Replicate). Gli errori sono {@code ReplicateException} (dominio) con il loro {@code Kind}. */
public interface IPredictionGateway {

    /**
     * Crea una prediction. Con {@code version} usa la versione pinnata, altrimenti l'ultima del modello ({@code "owner/name"}).
     * NON idempotente e a pagamento: l'implementazione non deve mai ritentare.
     */
    Prediction createPrediction(String model, String version, Map<String, Object> input);

    Prediction getPrediction(String externalId);

    /** Chiede di interrompere una prediction in corso; se e' gia' terminale il servizio risponde con un errore. */
    Prediction cancelPrediction(String externalId);
}
