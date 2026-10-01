package org.dual.replicate.app.chat.port.out;

import org.dual.replicate.app.chat.domain.event.ChatMessagePushEvent;

/** Avvisa chi ha la conversazione aperta di un turno scritto in background (es. l'esito di una generazione). */
public interface IChatNotifier {

    void chatMessage(ChatMessagePushEvent payload);
}
