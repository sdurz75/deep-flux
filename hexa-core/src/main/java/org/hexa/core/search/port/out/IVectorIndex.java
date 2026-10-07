package org.hexa.core.search.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hexa.core.search.domain.DocumentFilter;
import org.hexa.core.search.domain.IndexedDocument;
import org.hexa.core.search.domain.ScoredDocument;
import org.hexa.core.search.domain.SearchableDocument;
import org.hexa.core.kernel.Paged;

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

    /** I tag utente indicizzati (metadata {@code tags}) con il numero di documenti, i piu' usati prima. */
    Map<String, Long> tagCounts();

    long count();

    String embeddingModelId();

    int dimensions();
}
