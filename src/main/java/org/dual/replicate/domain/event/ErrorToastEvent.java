package org.dual.replicate.domain.event;

/**
 * Un errore da mostrare come toast in tutte le tab connesse a GET /events
 * (evento SSE "error-toast", vedi GenerationEventBroadcaster). {@code key}
 * identifica il toast lato client per la dedupe (SSE e HX-Trigger possono
 * portare lo stesso errore); {@code message} e' gia' tradotto.
 */
public record ErrorToastEvent(String key, String message) {
}
