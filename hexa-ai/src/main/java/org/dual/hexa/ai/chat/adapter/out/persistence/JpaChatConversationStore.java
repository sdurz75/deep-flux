package org.dual.hexa.ai.chat.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.ai.chat.domain.ChatConversation;
import org.dual.hexa.ai.chat.port.out.IChatConversationStore;
import org.springframework.stereotype.Component;

@Component
class JpaChatConversationStore implements IChatConversationStore {

    private final ChatConversationRepository repository;

    JpaChatConversationStore(ChatConversationRepository repository) {
        this.repository = repository;
    }

    @Override
    public ChatConversation save(ChatConversation conversation) {
        return repository.save(conversation);
    }

    @Override
    public Optional<ChatConversation> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<ChatConversation> findAllByRecency() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    @Override
    public Optional<ChatConversation> findMostRecent() {
        return repository.findFirstByOrderByUpdatedAtDesc();
    }

    @Override
    public List<ChatConversation> findAll() {
        return repository.findAll();
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void delete(ChatConversation conversation) {
        repository.delete(conversation);
    }
}
