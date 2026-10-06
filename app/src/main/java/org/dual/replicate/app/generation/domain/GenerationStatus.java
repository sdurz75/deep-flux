package org.dual.replicate.app.generation.domain;

/**
 * Stato di una richiesta di generazione immagine. Rispecchia il ciclo di
 * vita di una "prediction" Replicate, con l'aggiunta implicita del
 * download locale: si passa a SUCCEEDED solo dopo che l'immagine e'
 * stata scaricata e salvata su disco, non appena Replicate segnala
 * "succeeded".
 */
public enum GenerationStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED
}
