package org.hexa.core.search.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hexa.core.search.domain.DocumentFilter;
import org.hexa.core.search.domain.IndexStats;
import org.hexa.core.search.domain.IndexedDocument;
import org.hexa.core.search.domain.ScoredDocument;
import org.hexa.core.search.port.in.IArchiveIndex;
import org.hexa.core.search.domain.DocumentTypes;
import org.hexa.core.search.port.in.IArchiveSearch;
import org.hexa.core.search.port.in.ISearchableSource;
import org.hexa.core.search.port.out.IVectorIndex;
import org.hexa.core.kernel.Paged;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true", matchIfMissing = true)
public class ArchiveSearchService implements IArchiveSearch {

    private final IVectorIndex index;
    private final IArchiveIndex archiveIndex;
    private final List<ISearchableSource> sources;

    public ArchiveSearchService(IVectorIndex index, IArchiveIndex archiveIndex, List<ISearchableSource> sources) {
        this.index = index;
        this.archiveIndex = archiveIndex;
        this.sources = sources;
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

    @Override
    public List<String> types() {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        sources.forEach(source -> types.addAll(source.types()));
        types.add(DocumentTypes.NOTE);
        return List.copyOf(types);
    }

    @Override
    public String citation(String type, Map<String, Object> metadata) {
        return sources.stream().map(source -> source.citation(type, metadata)).flatMap(Optional::stream).findFirst()
                .orElseGet(() -> "[" + type + " #" + metadata.get("refId") + "]");
    }
}
