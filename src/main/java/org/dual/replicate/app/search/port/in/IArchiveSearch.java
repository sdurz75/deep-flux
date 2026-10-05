package org.dual.replicate.app.search.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.search.domain.DocumentFilter;
import org.dual.replicate.app.search.domain.IndexStats;
import org.dual.replicate.app.search.domain.IndexedDocument;
import org.dual.replicate.app.search.domain.ScoredDocument;
import org.dual.replicate.core.kernel.Paged;

/** Interrogare l'archivio: ricerca per significato, sfogliare, ispezionare. */
public interface IArchiveSearch {

    /** I migliori {@code limit} risultati per significato, sopra {@code minSimilarity} (0..1). */
    List<ScoredDocument> search(String query, DocumentFilter filter, double minSimilarity, int limit);

    /** TUTTA la classifica sopra {@code minSimilarity}. */
    List<ScoredDocument> searchAll(String query, DocumentFilter filter, double minSimilarity);

    /** Una pagina (da 0) dei documenti filtrati, piu' recenti prima; la pagina e' riportata nei limiti validi. */
    Paged<IndexedDocument> list(DocumentFilter filter, int pageIndex, int size);

    Optional<IndexedDocument> find(String id);

    IndexStats stats();

    /** I tag utente in uso (dei documenti indicizzati), i piu' usati prima: suggerimenti del filtro per tag. */
    List<String> tags();
}
