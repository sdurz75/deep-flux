package org.dual.hexa.ai.chat.domain;

import java.util.List;

/**
 * La chiamata all'assistente (LLM) e' fallita. Porta gli esiti (generazioni) che i tool avevano GIA' avviato nel turno: devono avere comunque
 * il loro watcher, anche se l'LLM e' fallito dopo.
 */
public class AssistantException extends RuntimeException {

    private final List<Long> startedOutcomeRefs;

    public AssistantException(Throwable cause, List<Long> startedOutcomeRefs) {
        super(cause.getMessage(), cause);
        this.startedOutcomeRefs = List.copyOf(startedOutcomeRefs);
    }

    public List<Long> startedOutcomeRefs() {
        return startedOutcomeRefs;
    }
}
