package org.dual.replicate.app.search.adapter.out.vector;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.IndexedDocument;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.out.IVectorIndex;
import org.dual.replicate.core.kernel.Paged;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * {@link IVectorIndex} sopra Spring AI: la ricerca per significato passa dall'interfaccia {@link VectorStore}, la scrittura da
 * {@link VectorIndexer}, la lettura/il listato da {@link VectorDocumentRepository}. Qui i tipi di dominio (filtro, documenti) si
 * traducono in {@link Document} e {@link Filter.Expression}: niente di Spring AI esce da questo package.
 */
class PgVectorIndex implements IVectorIndex {

    private final VectorStore vectorStore;
    private final VectorIndexer indexer;
    private final VectorDocumentRepository documents;

    PgVectorIndex(VectorStore vectorStore, VectorIndexer indexer, VectorDocumentRepository documents) {
        this.vectorStore = vectorStore;
        this.indexer = indexer;
        this.documents = documents;
    }

    @Override
    public void upsertIfChanged(List<SearchableDocument> toIndex) {
        indexer.upsertIfChanged(toIndex.stream()
                .map(d -> Document.builder().id(d.id()).text(d.text()).metadata(d.metadata()).build()).toList());
    }

    @Override
    public boolean reembed(String id) {
        return indexer.reembed(id);
    }

    @Override
    public void delete(List<String> ids) {
        indexer.delete(ids);
    }

    @Override
    public List<ScoredDocument> similar(String query, DocumentFilter filter, double minSimilarity, int limit) {
        SearchRequest.Builder request = SearchRequest.builder().query(query).topK(limit).similarityThreshold(minSimilarity);
        Filter.Expression expression = expression(filter);
        if (expression != null) {
            request.filterExpression(expression);
        }
        return vectorStore.similaritySearch(request.build()).stream()
                .map(d -> new ScoredDocument(new SearchableDocument(d.getId(), d.getText(), d.getMetadata()),
                        d.getScore() == null ? 0 : d.getScore()))
                .toList();
    }

    @Override
    public Optional<IndexedDocument> find(String id) {
        return documents.find(id);
    }

    @Override
    public Paged<IndexedDocument> list(DocumentFilter filter, int pageIndex, int size) {
        VectorDocumentRepository.Listing listing = documents.list(expression(filter), pageIndex + 1, size);
        return new Paged<>(listing.documents(), listing.page() - 1, size, listing.total());
    }

    @Override
    public List<String> idsOfType(String type) {
        return documents.idsOfType(type);
    }

    @Override
    public Map<String, Long> countsByType() {
        return documents.countsByType();
    }

    @Override
    public long count() {
        return documents.count();
    }

    @Override
    public String embeddingModelId() {
        return indexer.embeddingModelId();
    }

    @Override
    public int dimensions() {
        return VectorIndexer.DIMENSIONS;
    }

    /** {@code type} AND {@code createdAt} nel periodo AND {@code kind} AND {@code favourite}; {@code null} se nessun vincolo. */
    static Filter.Expression expression(DocumentFilter filter) {
        if (filter == null) {
            return null;
        }
        Filter.Expression result = null;
        if (filter.type() != null && !filter.type().isBlank()) {
            result = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("type"), new Filter.Value(filter.type()));
        }
        if (filter.from() != null) {
            result = and(result, new Filter.Expression(Filter.ExpressionType.GTE, new Filter.Key("createdAt"),
                    new Filter.Value(filter.from().toEpochMilli())));
        }
        if (filter.to() != null) {
            result = and(result, new Filter.Expression(Filter.ExpressionType.LTE, new Filter.Key("createdAt"),
                    new Filter.Value(filter.to().toEpochMilli())));
        }
        if (filter.kind() != null && !filter.kind().isBlank()) {
            result = and(result, new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("kind"), new Filter.Value(filter.kind())));
        }
        if (filter.favouriteOnly()) {
            result = and(result, new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("favourite"), new Filter.Value(true)));
        }
        return result;
    }

    private static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
        return left == null ? right : new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }
}
