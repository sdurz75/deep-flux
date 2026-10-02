package org.dual.replicate.app.chat.domain;

import java.util.List;

/**
 * Non porta un'eventuale immagine: il tool ritorna subito, prima che una generazione avviata in questo turno sia pronta: arriva
 * sempre in un secondo momento via push SSE, mai nella risposta sincrona. {@code actions} sono le azioni proposte (bottoni di
 * conferma), mai eseguite dal turno; non sono persistite: dopo un reload l'assistente le ripropone se serve.
 */
public record ChatReply(String text, List<Long> startedGenerationIds, List<ChatAction> actions) {

    public ChatReply(String text, List<Long> startedGenerationIds) {
        this(text, startedGenerationIds, List.of());
    }
}
