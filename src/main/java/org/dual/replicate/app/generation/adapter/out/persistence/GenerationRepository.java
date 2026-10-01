package org.dual.replicate.app.generation.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.domain.GalleryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface GenerationRepository extends JpaRepository<Generation, Long> {

    /** Usata dalla galleria: solo le generazioni completate, piu' recenti prima. */
    Page<Generation> findByStatusOrderByCreatedAtDesc(GenerationStatus status, Pageable pageable);

    /** Tab "Preferiti" della galleria: un item per ogni file con la star, di generazioni SUCCEEDED, piu' recenti prima. */
    @Query(value = "select new org.dual.replicate.app.generation.domain.GalleryItem(g, f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.dual.replicate.app.generation.domain.GenerationStatus.SUCCEEDED order by g.createdAt desc, f",
            countQuery = "select count(f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.dual.replicate.app.generation.domain.GenerationStatus.SUCCEEDED")
    Page<GalleryItem> findFavouriteItems(Pageable pageable);

    /** Usata dal listato /generations: tutte le generazioni, qualunque stato, piu' recenti prima. */
    Page<Generation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Generazioni non terminali di UN modello, create dopo {@code after}:
     * usata dal cap di concorrenza per-modello di GenerationService#create.
     * {@code after} esclude le righe piu' vecchie del timeout di
     * GenerationService (TIMEOUT): una riga cosi' vecchia diventera' FAILED
     * al prossimo refresh() ma nel frattempo non deve occupare uno slot,
     * altrimenti una generazione mai piu' pollata (tab chiusa, server
     * riavviato durante l'attesa) blocca quel modello a tempo indeterminato
     * — a differenza del vecchio conteggio via API Replicate, che si
     * autocorreggeva perche' Replicate stessa segna la prediction conclusa.
     */
    long countByModelAndStatusInAndCreatedAtAfter(String model, Collection<GenerationStatus> statuses, Instant after);

    /** Generazioni ancora in corso avviate da una conversazione di /deep-chat, per ripristinarne il placeholder al reload (stesso filtro "after" di sopra). */
    List<Generation> findByConversationIdAndStatusInAndCreatedAtAfterOrderByIdAsc(Long conversationId, Collection<GenerationStatus> statuses, Instant after);

    /** Recupero all'avvio (GenerationRecoveryService): tutte le generazioni in uno degli stati indicati. */
    List<Generation> findByStatusIn(Collection<GenerationStatus> statuses);

    /**
     * Id delle generazioni terminali di una conversazione di /deep-chat, completate prima di {@code before}, le piu' recenti prima
     * (il chiamante limita con il Pageable): la chat sottrae quelle che hanno gia' un turno (lo sweep di ChatRecoveryService).
     * Solo id: l'archivio delle generazioni cresce e questa query gira ogni pochi minuti. {@code before} evita di incrociare un
     * watcher che sta scrivendo il proprio turno proprio ora.
     */
    @Query("select g.id from Generation g where g.conversationId is not null and g.status in :statuses "
            + "and g.completedAt < :before order by g.id desc")
    List<Long> findTerminalIdsWithConversation(@Param("statuses") Collection<GenerationStatus> statuses,
                                               @Param("before") Instant before, Pageable pageable);

    /** Galleria contestuale di /deep-chat: le generazioni riuscite di una conversazione, in ordine cronologico. */
    List<Generation> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, GenerationStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Generation g set g.conversationId = null where g.conversationId = :conversationId")
    int clearConversation(@Param("conversationId") Long conversationId);
}
