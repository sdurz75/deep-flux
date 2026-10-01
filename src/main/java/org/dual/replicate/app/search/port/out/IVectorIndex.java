package org.dual.replicate.app.search.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.IndexedDocument;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.core.kernel.Paged;

/** L'indice vettoriale (embedding + ricerca per similarita'), nascosto dietro tipi di dominio. */
public interface IVectorIndex {

    /** Aggiunge o aggiorna i documenti, ri-embeddando solo quelli nuovi o con testo/modello cambiati. */
    void upsertIfChanged(List<SearchableDocument> documents);

    /** @return {@code false} se il documento non esiste */
    boolean reembed(String id);

    void delete(List<String> ids);

    List<ScoredDocument> similar(String query, DocumentFilter filter, double minSimilarity, int limit);

    Optional<IndexedDocument> find(String id);

    /** Pagina (da 0, riportata nei limiti) dei documenti filtrati, piu' recenti prima. */
    Paged<IndexedDocument> list(DocumentFilter filter, int pageIndex, int size);

    List<String> idsOfType(String type);

    Map<String, Long> countsByType();

    long count();

    String embeddingModelId();

    int dimensions();
}
