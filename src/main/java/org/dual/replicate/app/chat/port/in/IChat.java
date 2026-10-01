package org.dual.replicate.app.chat.port.in;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;

/** Un turno di conversazione con l'assistente. */
public interface IChat {

    /**
     * Risponde all'ultimo turno di {@code history} nella conversazione {@code conversationId}.
     * {@code selectedModel} e {@code generationParameters} sono le scelte della UI per l'eventuale generazione di immagini
     * (il modello e' SEMPRE quello scelto, mai deciso dall'LLM).
     *
     * @throws IllegalArgumentException se la conversazione non esiste
     * @throws org.dual.replicate.app.chat.domain.DeepChatFailedException se l'assistente fallisce (gia' registrato e scritto in cronologia)
     */
    ChatReply reply(Long conversationId, List<ChatTurn> history, String selectedModel, Map<String, Object> generationParameters);
}
