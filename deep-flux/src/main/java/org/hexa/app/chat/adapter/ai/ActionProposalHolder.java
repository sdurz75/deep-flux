package org.hexa.app.chat.adapter.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hexa.app.chat.domain.ChatAction;

/**
 * Canale di uscita dei tool di proposta ({@link ActionProposalTool}) verso SpringAiAssistant, come {@link GenerationResultHolder}:
 * il ToolContext e' solo caller -> tool. Una nuova istanza per turno; senza duplicati (l'LLM puo' riproporre la stessa azione) e
 * con un tetto, perche' ogni proposta e' un bottone in chat.
 */
public class ActionProposalHolder {

    public static final String CONTEXT_KEY = "actionProposalHolder";
    public static final int MAX_ACTIONS = 10;

    private final CopyOnWriteArrayList<ChatAction> actions = new CopyOnWriteArrayList<>();

    /** @return false se era gia' presente o il tetto e' raggiunto (la proposta non e' stata registrata) */
    public synchronized boolean add(ChatAction action) {
        return actions.size() < MAX_ACTIONS && actions.addIfAbsent(action);
    }

    public List<ChatAction> getActions() {
        return actions;
    }
}
