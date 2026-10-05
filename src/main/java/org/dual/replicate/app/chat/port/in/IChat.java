package org.dual.replicate.app.chat.port.in;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;

/** Un turno di conversazione con l'assistente. */
public interface IChat {

    /**
     * Risponde all'ultimo turno utente di {@code history} nella conversazione {@code conversationId}. Del {@code history} del client conta solo
     * quell'ultimo turno (il client manda solo l'ultimo messaggio): la cronologia per il modello la ricostruisce il servizio dal DB.
     * {@code selectedModel} e {@code generationParameters} sono le scelte della UI per l'eventuale generazione di immagini
     * (il modello e' SEMPRE quello scelto, mai deciso dall'LLM).
     *
     * @throws IllegalArgumentException se la conversazione non esiste
     * @throws org.dual.replicate.app.chat.domain.DeepChatFailedException se l'assistente fallisce (gia' registrato e scritto in cronologia)
     */
    ChatReply reply(Long conversationId, List<ChatTurn> history, String selectedModel, Map<String, Object> generationParameters);
}
