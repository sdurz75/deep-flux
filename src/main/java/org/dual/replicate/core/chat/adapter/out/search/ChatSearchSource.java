package org.dual.replicate.core.chat.adapter.out.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.core.chat.domain.event.ChatConversationChangedEvent;
import org.dual.replicate.core.chat.domain.ChatDocumentTypes;
import org.dual.replicate.core.search.domain.SearchableDocument;
import org.dual.replicate.core.search.port.in.ISearchableSource;
import org.dual.replicate.core.chat.domain.ChatConversation;
import org.dual.replicate.core.chat.domain.ChatMessage;
import org.dual.replicate.core.chat.port.out.IChatConversationStore;
import org.dual.replicate.core.chat.port.out.IChatMessageStore;
import org.dual.replicate.core.search.port.in.IArchiveIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Contributo della chat alla ricerca semantica: messaggi non di errore ({@code type=chat}) e titoli delle conversazioni ({@code type=conversation}). */
@Component
@Order(20)
public class ChatSearchSource implements ISearchableSource {

    private final IChatMessageStore messages;
    private final IChatConversationStore conversations;
    private final ObjectProvider<IArchiveIndex> index;

    public ChatSearchSource(IChatMessageStore messages, IChatConversationStore conversations, ObjectProvider<IArchiveIndex> index) {
        this.messages = messages;
        this.conversations = conversations;
        this.index = index;
    }

    // Dopo il commit (la riconciliazione gira su un altro thread); senza transazione in corso scatta subito.
    @TransactionalEventListener(fallbackExecution = true)
    void onConversationChanged(ChatConversationChangedEvent event) {
        index.ifAvailable(IArchiveIndex::reindexAsync);
    }

    @Override
    public Set<String> types() {
        return new java.util.LinkedHashSet<>(List.of(ChatDocumentTypes.CHAT, ChatDocumentTypes.CONVERSATION));
    }

    @Override
    public List<SearchableDocument> documents() {
        List<SearchableDocument> documents = new ArrayList<>();
        for (ChatMessage message : messages.findAll()) {
            if (!message.isError()) {
                documents.add(SearchableDocument.of("chatmessage:" + message.getId(), ChatDocumentTypes.CHAT, message.getId(),
                        message.getConversation().getId(), message.getCreatedAt(), message.getContent(),
                        Map.of("role", message.getRole().name())));
            }
        }
        for (ChatConversation conversation : conversations.findAll()) {
            List<String> tags = conversation.getTags().stream().sorted().toList();
            documents.add(SearchableDocument.of("conversation:" + conversation.getId(), ChatDocumentTypes.CONVERSATION,
                    conversation.getId(), conversation.getId(), conversation.getCreatedAt(), text(conversation.getTitle(), tags),
                    tags.isEmpty() ? Map.of() : Map.of("tags", tags)));
        }
        return documents;
    }

    /**
     * Il titolo; i tag utente NON entrano nel testo embeddato (il filtro per tag e' esatto, sul metadata {@code tags}). Unica eccezione:
     * una conversazione senza titolo ma taggata ha per testo i soli tag, perche' un testo vuoto la farebbe scartare dall'indice e il
     * filtro per tag non la troverebbe.
     */
    private static String text(String title, List<String> tags) {
        String base = title == null ? "" : title.strip();
        return base.isEmpty() && !tags.isEmpty() ? String.join(", ", tags) : base;
    }

    @Override
    public java.util.Optional<String> citation(String type, Map<String, Object> metadata) {
        return switch (type) {
            case ChatDocumentTypes.CONVERSATION -> java.util.Optional.of("[conversation #%s]".formatted(metadata.get("refId")));
            case ChatDocumentTypes.CHAT -> java.util.Optional.of("[chat, conversation #%s, %s]".formatted(metadata.get("conversationId"), metadata.get("role")));
            default -> java.util.Optional.empty();
        };
    }
}
