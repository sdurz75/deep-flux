package org.dual.replicate.app.chat.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.chat.domain.ChatConversation;
import org.dual.replicate.app.chat.port.out.IChatConversationStore;
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
    public void delete(ChatConversation conversation) {
        repository.delete(conversation);
    }
}
