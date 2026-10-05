package org.dual.replicate.app.chat.adapter.out.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.app.chat.domain.event.ChatConversationChangedEvent;
import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.ISearchableSource;
import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.dual.replicate.app.search.port.in.IArchiveIndex;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Contributo della chat alla ricerca semantica: messaggi non di errore ({@code type=chat}) e titoli delle conversazioni ({@code type=conversation}). */
@Component
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
            List<String> tags = conversation.getTags().stream().sorted().toList();
            documents.add(SearchableDocument.of("conversation:" + conversation.getId(), DocumentTypes.CONVERSATION,
                    conversation.getId(), conversation.getId(), conversation.getCreatedAt(), text(conversation.getTitle(), tags),
                    tags.isEmpty() ? Map.of() : Map.of("tags", tags)));
        }
        return documents;
    }

    /**
     * Titolo + tag utente come vocabolario d'indice (dopo {@code TAGS_SEPARATOR}, non e' testo per l'utente). Una conversazione senza
     * titolo ma taggata ha per testo i soli tag: senza, il testo vuoto la farebbe scartare dall'indice.
     */
    private static String text(String title, List<String> tags) {
        String base = title == null ? "" : title.strip();
        if (tags.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? String.join(", ", tags) : base + DocumentTypes.TAGS_SEPARATOR + String.join(", ", tags);
    }
}
