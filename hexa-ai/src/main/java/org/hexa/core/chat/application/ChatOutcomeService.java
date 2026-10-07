package org.hexa.core.chat.application;

import java.util.Collection;
import java.util.List;

import org.hexa.core.chat.domain.ChatConversation;
import org.hexa.core.chat.domain.ChatMessage;
import org.hexa.core.chat.domain.ChatMessageRole;
import org.hexa.core.chat.domain.ChatOutcome;
import org.hexa.core.chat.domain.event.ChatMessagePushEvent;
import org.hexa.core.chat.port.in.IChatOutcomes;
import org.hexa.core.chat.port.out.IChatConversationStore;
import org.hexa.core.chat.port.out.IChatMessageStore;
import org.hexa.core.chat.port.out.IChatNotifier;
import org.springframework.stereotype.Service;

@Service
public class ChatOutcomeService implements IChatOutcomes {

    private final IChatConversationStore conversations;
    private final IChatMessageStore messages;
    private final IChatNotifier notifier;

    public ChatOutcomeService(IChatConversationStore conversations, IChatMessageStore messages, IChatNotifier notifier) {
        this.conversations = conversations;
        this.messages = messages;
        this.notifier = notifier;
    }

    /** {@code synchronized}: controllo e scrittura dell'idempotenza non sono atomici sul DB, e chi attende e il recupero possono incrociarsi. */
    @Override
    public synchronized boolean append(Long conversationId, ChatOutcome outcome) {
        if (messages.existsByOutcomeRef(outcome.ref())) {
            return false;
        }
        ChatConversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null) {
            // Conversazione cancellata mentre l'esito era in corso.
            return false;
        }
        conversation.touch();
        conversations.save(conversation);
        messages.save(new ChatMessage(conversation, ChatMessageRole.AI, outcome.text(), outcome.ref()));
        notifier.chatMessage(new ChatMessagePushEvent(conversationId, outcome.ref(), outcome.text(), outcome.files()));
        return true;
    }

    @Override
    public List<Long> refsWithOutcome(Collection<Long> refs) {
        return messages.outcomeRefsWithTurn(refs);
    }
}
