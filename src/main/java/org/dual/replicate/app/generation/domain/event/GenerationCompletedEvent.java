package org.dual.replicate.app.generation.domain.event;

import org.dual.replicate.app.generation.adapter.out.push.GalleryPushNotifier;
import org.dual.replicate.app.generation.application.GenerationService;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.service.DeepChatGenerationWatcher;

/**
 * Pubblicato da GenerationService esattamente nel momento in cui una
 * generazione transita a uno stato terminale (successo o fallimento),
 * qualunque sia stato il percorso che l'ha portata li' (polling
 * client-side di /generations/{id}, o watch in background da
 * /deep-chat, vedi DeepChatGenerationWatcher). Consumato da
 * GalleryPushNotifier per notificare via SSE chi ha /gallery
 * aperta: un solo punto d'aggancio per entrambi i percorsi.
 */
public record GenerationCompletedEvent(Generation generation) {
}
