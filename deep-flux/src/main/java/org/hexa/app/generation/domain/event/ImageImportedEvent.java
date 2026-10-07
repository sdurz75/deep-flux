package org.hexa.app.generation.domain.event;

/**
 * Pubblicato da ImportedImageService quando un'immagine importata e' stata salvata: avvia l'analisi di contenuto in background
 * (ImportAnalysisListener). Porta solo l'id: l'analisi rilegge lo stato dal DB.
 */
public record ImageImportedEvent(Long generationId) {
}
