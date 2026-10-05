package org.dual.replicate.app.chat.port.out;

import java.util.List;
import java.util.Map;

import org.dual.replicate.app.chat.domain.AssistantException;
import org.dual.replicate.app.chat.domain.ChatReply;
import org.dual.replicate.app.chat.domain.ChatTurn;

/**
 * L'assistente (LLM con i suoi tool: ricerca web, generazione immagini, lettura e cura dell'archivio). Un turno e' una chiamata sincrona: i tool
 * possono avviare generazioni, di cui la risposta riporta gli id.
 */
public interface IAssistant {

    /**
     * @param conversationId la conversazione del turno (per i tool che ragionano sulla conversazione corrente)
     * @param history la cronologia costruita dal SERVER per il modello (una finestra, con le note {@code system} sugli esiti delle
     *                generazioni), l'ultimo turno e' quello a cui rispondere
     * @param selectedModel il modello di generazione scelto nella UI (nota di contesto per l'LLM e modello usato dal tool)
     * @param generationParameters i parametri del pannello impostazioni, nel vocabolario Replicate (mai visti dall'LLM)
     * @throws AssistantException se la chiamata fallisce; porta gli id delle generazioni gia' avviate
     */
    ChatReply respond(Long conversationId, List<ChatTurn> history, String selectedModel, Map<String, Object> generationParameters);
}
