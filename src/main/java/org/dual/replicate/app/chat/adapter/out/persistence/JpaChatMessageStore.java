package org.dual.replicate.app.chat.adapter.out.persistence;

import java.util.Collection;
import java.util.List;

import org.dual.replicate.app.chat.domain.ChatMessage;
import org.dual.replicate.app.chat.port.out.IChatMessageStore;
import org.springframework.stereotype.Component;

@Component
class JpaChatMessageStore implements IChatMessageStore {

    private final ChatMessageRepository repository;

    JpaChatMessageStore(ChatMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        return repository.save(message);
    }

    @Override
    public boolean existsByGenerationId(Long generationId) {
        return repository.existsByGenerationId(generationId);
    }

    @Override
    public List<ChatMessage> findByConversation(Long conversationId) {
        return repository.findByConversationIdOrderByIdAsc(conversationId);
    }

    @Override
    public List<Long> generationIdsWithTurn(Collection<Long> generationIds) {
        return repository.findGenerationIdsIn(generationIds);
    }

    @Override
    public void deleteAll(List<ChatMessage> messages) {
        repository.deleteAll(messages);
    }

    @Override
    public List<ChatMessage> findAll() {
        return repository.findAll();
    }
}
