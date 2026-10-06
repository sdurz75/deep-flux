package org.dual.replicate.core.chat.adapter.out.push;

import org.dual.replicate.core.chat.port.out.IChatNotifier;
import org.dual.replicate.core.push.port.in.IClientPush;
import org.dual.replicate.core.chat.domain.event.ChatMessagePushEvent;
import org.springframework.stereotype.Component;

/**
 * Il lato chat del push: consegna a {@link IClientPush} (core.push) l'evento SSE {@code chat-message} (un turno scritto in
 * background, es. l'esito di una generazione). Il filtro per conversazione avviene lato client, confrontando conversationId con
 * quella attualmente aperta (app single-user, nessuno scoping).
 */
@Component
public class ChatPushNotifier implements IChatNotifier {

    static final String CHAT_MESSAGE = "chat-message";

    private final IClientPush push;

    public ChatPushNotifier(IClientPush push) {
        this.push = push;
    }

    @Override
    public void chatMessage(ChatMessagePushEvent payload) {
        push.emit(CHAT_MESSAGE, payload);
    }
}
