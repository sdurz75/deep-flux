package org.dual.replicate.app.chat.domain;

import java.util.List;

/**
 * Non porta un'eventuale immagine: il tool ritorna subito, prima che una generazione avviata in questo turno sia pronta: arriva
 * sempre in un secondo momento via push SSE, mai nella risposta sincrona.
 */
public record ChatReply(String text, List<Long> startedGenerationIds) {
}
