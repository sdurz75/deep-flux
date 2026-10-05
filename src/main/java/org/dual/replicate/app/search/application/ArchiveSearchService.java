package org.dual.replicate.app.search.application;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.IndexStats;
import org.dual.replicate.app.search.domain.IndexedDocument;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.app.search.port.in.IArchiveIndex;
import org.dual.replicate.app.search.port.in.IArchiveSearch;
import org.dual.replicate.app.search.port.out.IVectorIndex;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveSearchService implements IArchiveSearch {

    private final IVectorIndex index;
    private final IArchiveIndex archiveIndex;

    public ArchiveSearchService(IVectorIndex index, IArchiveIndex archiveIndex) {
        this.index = index;
        this.archiveIndex = archiveIndex;
    }

    @Override
    public List<ScoredDocument> search(String query, DocumentFilter filter, double minSimilarity, int limit) {
        return index.similar(query, filter, minSimilarity, limit);
    }

    @Override
    public List<ScoredDocument> searchAll(String query, DocumentFilter filter, double minSimilarity) {
        // topK = dimensione dell'indice: la classifica intera, da paginare a chi chiama.
        return index.similar(query, filter, minSimilarity, (int) Math.max(1, index.count()));
    }

    @Override
    public Paged<IndexedDocument> list(DocumentFilter filter, int pageIndex, int size) {
        return index.list(filter, pageIndex, size);
    }

    @Override
    public Optional<IndexedDocument> find(String id) {
        return index.find(id);
    }

    @Override
    public List<String> tags() {
        return List.copyOf(index.tagCounts().keySet());
    }

    @Override
    public IndexStats stats() {
        var counts = index.countsByType();
        return new IndexStats(counts.values().stream().mapToLong(Long::longValue).sum(), counts, index.embeddingModelId(),
                index.dimensions(), archiveIndex.isRunning());
    }
}
