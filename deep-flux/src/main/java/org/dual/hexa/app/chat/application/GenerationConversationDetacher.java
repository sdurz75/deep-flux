package org.dual.hexa.app.chat.application;

import org.dual.hexa.ai.chat.domain.event.ChatConversationDeletedEvent;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Lato app della cancellazione di una conversazione: le generazioni nate li' restano (galleria) e si scollegano soltanto. */
@Component
class GenerationConversationDetacher {

    private final IGenerations generations;

    GenerationConversationDetacher(IGenerations generations) {
        this.generations = generations;
    }

    /** Sincrono, nella transazione di chi cancella: o si scollega e si cancella, o niente. */
    @EventListener
    void on(ChatConversationDeletedEvent event) {
        generations.detachFromConversation(event.conversationId());
    }
}
