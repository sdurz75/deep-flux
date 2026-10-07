package org.dual.hexa.app.chat.application;

import org.dual.hexa.ai.chat.domain.event.ChatOutcomesStartedEvent;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.app.shared.domain.AppEventSubjects;
import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lato app degli esiti avviati da un turno di chat: i riferimenti sono id di generazioni, che qui si agganciano alla conversazione e di
 * cui si avvia l'attesa in background ({@link ChatGenerationWatcher}).
 */
@Component
class GenerationOutcomeStarter {

    private final IGenerations generations;
    private final ChatGenerationWatcher watcher;
    private final ISystemEvents systemEvents;

    GenerationOutcomeStarter(IGenerations generations, ChatGenerationWatcher watcher, ISystemEvents systemEvents) {
        this.generations = generations;
        this.watcher = watcher;
        this.systemEvents = systemEvents;
    }

    @EventListener
    void on(ChatOutcomesStartedEvent event) {
        for (Long id : event.refs()) {
            // Per id: un fallimento su uno non deve impedire agli altri di avere il watcher (il recupero riprende comunque le orfane).
            try {
                generations.attachToConversation(id, event.conversationId());
                watcher.watch(id, event.conversationId(), event.locale());
            } catch (RuntimeException e) {
                systemEvents.record(CoreEventSource.INTERNAL, "watchStart", e, AppEventSubjects.of(id, event.conversationId()));
            }
        }
    }
}
