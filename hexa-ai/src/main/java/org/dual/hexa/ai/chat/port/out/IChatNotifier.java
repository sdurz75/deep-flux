package org.dual.hexa.ai.chat.port.out;

import org.dual.hexa.ai.chat.domain.event.ChatMessagePushEvent;

/** Avvisa chi ha la conversazione aperta di un turno scritto in background (es. l'esito di una generazione). */
public interface IChatNotifier {

    void chatMessage(ChatMessagePushEvent payload);
}
