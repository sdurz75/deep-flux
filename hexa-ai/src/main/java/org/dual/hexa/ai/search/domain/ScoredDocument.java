package org.dual.hexa.ai.search.domain;

/** Un risultato di ricerca: il documento e la similarita' (0..1). */
public record ScoredDocument(SearchableDocument document, double score) {
}
