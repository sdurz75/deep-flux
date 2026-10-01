package org.dual.replicate.search.vector;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Decorator di {@link EmbeddingModel} per i modelli e5: i documenti da indicizzare vanno prefissati {@code "passage: "} e le
 * ricerche {@code "query: "} (senza, la qualita' crolla). {@code PgVectorStore} embedda i documenti con
 * {@link #getEmbeddingContent(Document)} e le ricerche con {@link #embed(String)}: sono i due soli punti in cui il prefisso si
 * puo' distinguere, e restano interni allo store (chi lo usa passa il testo nudo). Non e' un bean: lo vede solo lo store.
 */
class E5PrefixEmbeddingModel implements EmbeddingModel {

    static final String PASSAGE_PREFIX = "passage: ";
    static final String QUERY_PREFIX = "query: ";

    private final EmbeddingModel delegate;

    E5PrefixEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    /** Percorso della ricerca. */
    @Override
    public float[] embed(String text) {
        return delegate.embed(QUERY_PREFIX + text);
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(getEmbeddingContent(document));
    }

    /** Percorso dell'indicizzazione (usato dal default di {@code embed(List<Document>, ...)}). */
    @Override
    public String getEmbeddingContent(Document document) {
        return PASSAGE_PREFIX + document.getText();
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
