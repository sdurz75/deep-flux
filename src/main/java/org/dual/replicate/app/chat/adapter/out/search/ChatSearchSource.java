package org.dual.replicate.app.chat.adapter.out.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.ISearchableSource;
import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.springframework.stereotype.Component;

/** Contributo della chat alla ricerca semantica: messaggi non di errore ({@code type=chat}) e titoli delle conversazioni ({@code type=conversation}). */
@Component
public class ChatSearchSource implements ISearchableSource {

    private final IChatMessageStore messages;
    private final IChatConversationStore conversations;

    public ChatSearchSource(IChatMessageStore messages, IChatConversationStore conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    @Override
    public Set<String> types() {
        return Set.of(DocumentTypes.CHAT, DocumentTypes.CONVERSATION);
    }

    @Override
    public List<SearchableDocument> documents() {
        List<SearchableDocument> documents = new ArrayList<>();
        for (ChatMessage message : messages.findAll()) {
            if (!message.isError()) {
                documents.add(SearchableDocument.of("chatmessage:" + message.getId(), DocumentTypes.CHAT, message.getId(),
                        message.getConversation().getId(), message.getCreatedAt(), message.getContent(),
                        Map.of("role", message.getRole().name())));
            }
        }
        for (ChatConversation conversation : conversations.findAll()) {
            documents.add(SearchableDocument.of("conversation:" + conversation.getId(), DocumentTypes.CONVERSATION,
                    conversation.getId(), conversation.getId(), conversation.getCreatedAt(), conversation.getTitle(), Map.of()));
        }
        return documents;
    }
}
