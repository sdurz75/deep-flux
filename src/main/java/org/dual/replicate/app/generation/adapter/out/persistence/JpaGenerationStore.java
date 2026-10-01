package org.dual.replicate.app.generation.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** {@link IGenerationStore} su Spring Data JPA (tabella {@code generation} e collegate). */
@Component
class JpaGenerationStore implements IGenerationStore {

    private final GenerationRepository repository;

    JpaGenerationStore(GenerationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Generation save(Generation generation) {
        return repository.save(generation);
    }

    @Override
    public Optional<Generation> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<Generation> findAllById(Collection<Long> ids) {
        return repository.findAllById(ids);
    }

    @Override
    public List<Generation> findAll() {
        return repository.findAll();
    }

    @Override
    public boolean existsById(Long id) {
        return repository.existsById(id);
    }

    @Override
    public void deleteAllById(Collection<Long> ids) {
        repository.deleteAllById(ids);
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }

    @Override
    public Paged<Generation> pageByStatus(GenerationStatus status, int pageIndex, int pageSize) {
        return paged(repository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(pageIndex, pageSize)));
    }

    @Override
    public Paged<GalleryItem> pageFavouriteItems(int pageIndex, int pageSize) {
        return paged(repository.findFavouriteItems(PageRequest.of(pageIndex, pageSize)));
    }

    @Override
    public Paged<Generation> pageAll(int pageIndex, int pageSize) {
        return paged(repository.findAllByOrderByCreatedAtDesc(PageRequest.of(pageIndex, pageSize)));
    }

    @Override
    public long countByModelAndStatusInAndCreatedAtAfter(String model, Collection<GenerationStatus> statuses, Instant after) {
        return repository.countByModelAndStatusInAndCreatedAtAfter(model, statuses, after);
    }

    @Override
    public List<Generation> findByConversationIdAndStatusInAndCreatedAtAfterOrderByIdAsc(Long conversationId,
            Collection<GenerationStatus> statuses, Instant after) {
        return repository.findByConversationIdAndStatusInAndCreatedAtAfterOrderByIdAsc(conversationId, statuses, after);
    }

    @Override
    public List<Generation> findByStatusIn(Collection<GenerationStatus> statuses) {
        return repository.findByStatusIn(statuses);
    }

    @Override
    public List<Long> findTerminalIdsWithConversation(Collection<GenerationStatus> statuses, Instant before, int limit) {
        return repository.findTerminalIdsWithConversation(statuses, before, PageRequest.of(0, limit));
    }

    @Override
    public List<Generation> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, GenerationStatus status) {
        return repository.findByConversationIdAndStatusOrderByIdAsc(conversationId, status);
    }

    private static <T> Paged<T> paged(Page<T> page) {
        return new Paged<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
