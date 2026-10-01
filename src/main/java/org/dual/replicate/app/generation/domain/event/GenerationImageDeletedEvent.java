package org.dual.replicate.app.generation.domain.event;

/** Una singola immagine e' stata rimossa da una Generation ANCORA esistente (GenerationService#deleteImage, caso non a cascata: vedi GenerationsDeletedEvent per il caso in cui invece l'intera generazione sparisce). */
public record GenerationImageDeletedEvent(Long generationId) {
}
