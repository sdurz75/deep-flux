package org.hexa.app.generation.domain.event;

import org.hexa.app.generation.domain.Generation;

/**
 * Pubblicato da GenerationService esattamente nel momento in cui una
 * generazione transita a uno stato terminale (successo o fallimento),
 * qualunque sia stato il percorso che l'ha portata li' (polling
 * client-side di /generations/{id}, o watch in background da
 * /deep-chat, vedi ChatGenerationWatcher). Consumato da
 * GalleryPushNotifier per notificare via SSE chi ha /gallery
 * aperta: un solo punto d'aggancio per entrambi i percorsi.
 */
public record GenerationCompletedEvent(Generation generation) {
}
