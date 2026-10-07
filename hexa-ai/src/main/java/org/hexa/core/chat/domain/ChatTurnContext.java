package org.hexa.core.chat.domain;

/**
 * Chiavi del contesto di un turno, la mappa opaca che chi ospita la chat (l'app) compone con {@code IChatTurnContributor} e che
 * l'assistente passa ai tool come {@code ToolContext}. La chat conosce solo queste: tutto il resto e' un accordo fra un contributore e i
 * suoi tool.
 */
public final class ChatTurnContext {

    /** La conversazione del turno ({@code Long}), messa dall'assistente per i tool che ragionano su quella corrente. */
    public static final String CONVERSATION_ID = "conversationId";

    /** Note per il modello ({@code List<String>}) anteposte alla cronologia come messaggi di sistema; non arrivano ai tool. */
    public static final String SYSTEM_NOTES = "systemNotes";

    private ChatTurnContext() {
    }
}
