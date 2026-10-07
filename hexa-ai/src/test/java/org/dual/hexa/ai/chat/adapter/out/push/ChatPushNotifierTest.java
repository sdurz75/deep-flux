package org.dual.hexa.ai.chat.adapter.out.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.dual.hexa.core.push.port.in.IClientPush;
import org.dual.hexa.ai.chat.domain.event.ChatMessagePushEvent;
import org.junit.jupiter.api.Test;

class ChatPushNotifierTest {

    private final IClientPush push = mock(IClientPush.class);
    private final ChatPushNotifier notifier = new ChatPushNotifier(push);

    @Test
    void chatMessageIsEmittedWithItsPayload() {
        ChatMessagePushEvent payload = new ChatMessagePushEvent(1L, null, "Immagine pronta", null);

        notifier.chatMessage(payload);

        verify(push).emit("chat-message", payload);
    }
}
