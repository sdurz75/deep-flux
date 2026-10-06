package org.dual.replicate.core.chat.port.in;

import java.util.List;
import java.util.Map;

import org.dual.replicate.core.chat.domain.ChatReply;
import org.dual.replicate.core.chat.domain.ChatTurn;

/** Un turno di conversazione con l'assistente. */
public interface IChat {

    /**
     * Risponde all'ultimo turno utente di {@code history} nella conversazione {@code conversationId}. Del {@code history} del client conta solo
     * quell'ultimo turno (il client manda solo l'ultimo messaggio): la cronologia per il modello la ricostruisce il servizio dal DB.
     * {@code clientSettings} sono le impostazioni opache che il client manda col messaggio: la chat non le interpreta, le trasformano in
     * contesto del turno i {@code IChatTurnContributor} (per l'app: modello e parametri di generazione scelti nella UI).
     *
     * @throws IllegalArgumentException se la conversazione non esiste
     * @throws org.dual.replicate.core.chat.domain.DeepChatFailedException se l'assistente fallisce (gia' registrato e scritto in cronologia)
     */
    ChatReply reply(Long conversationId, List<ChatTurn> history, Map<String, Object> clientSettings);
}
