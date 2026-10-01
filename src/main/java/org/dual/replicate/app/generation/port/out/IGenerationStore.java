package org.dual.replicate.app.generation.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.core.kernel.Paged;

/** Persistenza delle generazioni. */
public interface IGenerationStore {

    Generation save(Generation generation);

    Optional<Generation> findById(Long id);

    /** Le generazioni esistenti fra gli id dati (i mancanti sono assenti). */
    List<Generation> findAllById(Collection<Long> ids);

    List<Generation> findAll();

    boolean existsById(Long id);

    void deleteAllById(Collection<Long> ids);

    /** Svuota la tabella (test e reset). */
    void deleteAll();

    /** Usata dalla galleria: solo le generazioni completate, piu' recenti prima. */
    Paged<Generation> pageByStatus(GenerationStatus status, int pageIndex, int pageSize);

    /** Tab "Preferiti" della galleria: un item per ogni file con la star, di generazioni SUCCEEDED, piu' recenti prima. */
    Paged<GalleryItem> pageFavouriteItems(int pageIndex, int pageSize);

    /** Listato /generations: tutte le generazioni, qualunque stato, piu' recenti prima. */
    Paged<Generation> pageAll(int pageIndex, int pageSize);

    /**
     * Generazioni non terminali di UN modello create dopo {@code after}: il cap di concorrenza per modello. {@code after} esclude le
     * righe piu' vecchie del timeout (una riga cosi' vecchia diventera' FAILED al prossimo refresh e non deve occupare uno slot).
     */
    long countByModelAndStatusInAndCreatedAtAfter(String model, Collection<GenerationStatus> statuses, Instant after);

    /** Generazioni ancora in corso avviate da una conversazione, per ripristinarne il placeholder al reload. */
    List<Generation> findByConversationIdAndStatusInAndCreatedAtAfterOrderByIdAsc(Long conversationId,
                                                                                   Collection<GenerationStatus> statuses, Instant after);

    List<Generation> findByStatusIn(Collection<GenerationStatus> statuses);

    /** Id (i piu' recenti, al massimo {@code limit}) delle generazioni terminali di una conversazione completate prima di {@code before}. */
    List<Long> findTerminalIdsWithConversation(Collection<GenerationStatus> statuses, Instant before, int limit);

    /** Galleria contestuale: le generazioni di uno stato di una conversazione, in ordine cronologico. */
    List<Generation> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, GenerationStatus status);

    /** Azzera {@code conversationId} di tutte le generazioni della conversazione. */
    void clearConversation(Long conversationId);
}
