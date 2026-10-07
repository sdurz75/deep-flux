package org.hexa.core.chat.port.out;

import java.util.List;
import java.util.Map;

import org.hexa.core.chat.domain.AssistantException;
import org.hexa.core.chat.domain.ChatReply;
import org.hexa.core.chat.domain.ChatTurn;

/**
 * L'assistente (LLM con i suoi tool: ricerca web, generazione immagini, lettura e cura dell'archivio). Un turno e' una chiamata sincrona: i tool
 * possono avviare generazioni, di cui la risposta riporta gli id.
 */
public interface IAssistant {

    /**
     * @param conversationId la conversazione del turno (per i tool che ragionano sulla conversazione corrente)
     * @param history la cronologia costruita dal SERVER per il modello (una finestra, con le note {@code system} sugli esiti delle
     *                generazioni), l'ultimo turno e' quello a cui rispondere
     * @param turnContext il contesto del turno composto dai {@code IChatTurnContributor} (chiavi di {@code ChatTurnContext} comprese):
     *                    arriva ai tool, mai al modello LLM
     * @throws AssistantException se la chiamata fallisce; porta i riferimenti degli esiti gia' avviati
     */
    ChatReply respond(Long conversationId, List<ChatTurn> history, Map<String, Object> turnContext);
}
