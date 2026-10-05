package org.dual.replicate.app.generation.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.AnalysisStatus;
import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.GenerationOrigin;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.out.IGenerationStore;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    public Paged<Generation> pageSucceeded(GenerationKind kind, GenerationOrigin origin, int pageIndex, int pageSize) {
        PageRequest page = PageRequest.of(pageIndex, pageSize);
        GenerationStatus succeeded = GenerationStatus.SUCCEEDED;
        if (kind == null && origin == null) {
            return pageByStatus(succeeded, pageIndex, pageSize);
        }
        if (kind == null) {
            return paged(repository.findByStatusAndOriginOrderByCreatedAtDesc(succeeded, origin, page));
        }
        if (origin == null) {
            return paged(repository.findByStatusAndKindOrderByCreatedAtDesc(succeeded, kind, page));
        }
        return paged(repository.findByStatusAndKindAndOriginOrderByCreatedAtDesc(succeeded, kind, origin, page));
    }

    @Override
    public List<Generation> findByAnalysisStatus(AnalysisStatus status) {
        return repository.findByAnalysisStatus(status);
    }

    @Override
    public List<Generation> findByAnalysisStatusAndCreatedAtBefore(AnalysisStatus status, Instant before) {
        return repository.findByAnalysisStatusAndCreatedAtBefore(status, before);
    }

    @Override
    public Paged<GalleryItem> pageFavouriteItems(int pageIndex, int pageSize) {
        return paged(repository.findFavouriteItems(PageRequest.of(pageIndex, pageSize)));
    }

    @Override
    public Paged<Generation> pageSucceededByTag(String tag, boolean importedOnly, int pageIndex, int pageSize) {
        return paged(repository.findSucceededByTag(tag, importedOnly, PageRequest.of(pageIndex, pageSize)));
    }

    @Override
    public Paged<GalleryItem> pageFavouriteItemsByTag(String tag, int pageIndex, int pageSize) {
        return paged(repository.findFavouriteItemsByTag(tag, PageRequest.of(pageIndex, pageSize)));
    }

    @Override
    public List<String> distinctTags() {
        java.util.TreeSet<String> tags = new java.util.TreeSet<>(repository.findDistinctGenerationTags());
        tags.addAll(repository.findDistinctFileTags());
        return List.copyOf(tags);
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
    public BigDecimal sumCostSince(Instant since) {
        return repository.sumCostSince(since);
    }

    @Override
    public List<Generation> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, GenerationStatus status) {
        return repository.findByConversationIdAndStatusOrderByIdAsc(conversationId, status);
    }

    @Override
    @Transactional
    public void clearConversation(Long conversationId) {
        repository.clearConversation(conversationId);
    }

    private static <T> Paged<T> paged(Page<T> page) {
        return new Paged<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
