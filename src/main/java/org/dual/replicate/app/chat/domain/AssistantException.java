package org.dual.replicate.app.chat.domain;

import java.util.List;

/**
 * La chiamata all'assistente (LLM) e' fallita. Porta le generazioni che i tool avevano GIA' avviato nel turno: devono avere comunque
 * il loro watcher, anche se l'LLM e' fallito dopo.
 */
public class AssistantException extends RuntimeException {

    private final List<Long> startedGenerationIds;

    public AssistantException(Throwable cause, List<Long> startedGenerationIds) {
        super(cause.getMessage(), cause);
        this.startedGenerationIds = List.copyOf(startedGenerationIds);
    }

    public List<Long> startedGenerationIds() {
        return startedGenerationIds;
    }
}
