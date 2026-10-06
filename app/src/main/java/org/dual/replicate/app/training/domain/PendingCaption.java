package org.dual.replicate.app.training.domain;

/** Un'immagine di una bozza la cui didascalia automatica e' ancora da scrivere: serve al recupero dei lavori persi (riavvio, evento perso). */
public record PendingCaption(Long datasetId, Long imageId) {
}
