package org.dual.replicate.app.training.domain.event;

/**
 * Un'immagine di una bozza ha bisogno della didascalia automatica (appena caricata, ritagliata di nuovo, "Rigenera"). La riga e' gia' salvata con
 * lo stato {@code PENDING}: l'evento avvia solo il lavoro in background, che e' idempotente e rilegge sempre lo stato dal DB.
 */
public record CaptionRequestedEvent(Long datasetId, Long imageId) {
}
