package org.dual.hexa.ai.chat.adapter.out.persistence;

import java.util.Collection;
import java.util.List;

import org.dual.hexa.ai.chat.domain.ChatMessage;
import org.dual.hexa.ai.chat.port.out.IChatMessageStore;
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
    public boolean existsByOutcomeRef(Long outcomeRef) {
        return repository.existsByOutcomeRef(outcomeRef);
    }

    @Override
    public List<ChatMessage> findByConversation(Long conversationId) {
        return repository.findByConversationIdOrderByIdAsc(conversationId);
    }

    @Override
    public List<Long> outcomeRefsWithTurn(Collection<Long> outcomeRefs) {
        return repository.findOutcomeRefsIn(outcomeRefs);
    }

    @Override
    public void deleteAll(List<ChatMessage> messages) {
        repository.deleteAll(messages);
    }

    @Override
    public List<ChatMessage> findAll() {
        return repository.findAll();
    }

    @Override
    public long count() {
        return repository.count();
    }
}
