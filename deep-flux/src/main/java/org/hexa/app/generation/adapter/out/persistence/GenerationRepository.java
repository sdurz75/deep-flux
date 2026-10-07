package org.hexa.app.generation.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.AnalysisStatus;
import org.hexa.app.generation.domain.GenerationKind;
import org.hexa.app.generation.domain.GenerationOrigin;
import org.hexa.app.generation.domain.GenerationStatus;
import org.hexa.app.generation.domain.GalleryItem;
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

    Page<Generation> findByStatusAndOriginOrderByCreatedAtDesc(GenerationStatus status, GenerationOrigin origin, Pageable pageable);

    Page<Generation> findByStatusAndKindOrderByCreatedAtDesc(GenerationStatus status, GenerationKind kind, Pageable pageable);

    Page<Generation> findByStatusAndKindAndOriginOrderByCreatedAtDesc(GenerationStatus status, GenerationKind kind,
                                                                      GenerationOrigin origin, Pageable pageable);

    List<Generation> findByAnalysisStatus(AnalysisStatus status);

    List<Generation> findByAnalysisStatusAndCreatedAtBefore(AnalysisStatus status, Instant before);

    /** Tab "Preferiti" della galleria: un item per ogni file con la star, di generazioni SUCCEEDED, piu' recenti prima. */
    @Query(value = "select new org.hexa.app.generation.domain.GalleryItem(g, f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.hexa.app.generation.domain.GenerationStatus.SUCCEEDED order by g.createdAt desc, f",
            countQuery = "select count(f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.hexa.app.generation.domain.GenerationStatus.SUCCEEDED")
    Page<GalleryItem> findFavouriteItems(Pageable pageable);

    /**
     * Galleria filtrata per tag utente: generazioni riuscite col tag sulla generazione O su uno dei suoi file; {@code importedOnly}
     * restringe alle importate.
     */
    @Query(value = "select g from Generation g where g.status = org.hexa.app.generation.domain.GenerationStatus.SUCCEEDED "
            + "and (:importedOnly = false or g.origin = org.hexa.app.generation.domain.GenerationOrigin.IMPORTED) "
            + "and (:tag member of g.tags or exists (select 1 from g.fileTags ft where ft.tag = :tag)) order by g.createdAt desc",
            countQuery = "select count(g) from Generation g where g.status = org.hexa.app.generation.domain.GenerationStatus.SUCCEEDED "
            + "and (:importedOnly = false or g.origin = org.hexa.app.generation.domain.GenerationOrigin.IMPORTED) "
            + "and (:tag member of g.tags or exists (select 1 from g.fileTags ft where ft.tag = :tag))")
    Page<Generation> findSucceededByTag(@Param("tag") String tag, @Param("importedOnly") boolean importedOnly, Pageable pageable);

    /** Tab "Preferiti" filtrata per tag: tag della generazione o di QUEL file. */
    @Query(value = "select new org.hexa.app.generation.domain.GalleryItem(g, f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.hexa.app.generation.domain.GenerationStatus.SUCCEEDED "
            + "and (:tag member of g.tags or exists (select 1 from g.fileTags ft where ft.filename = f and ft.tag = :tag)) "
            + "order by g.createdAt desc, f",
            countQuery = "select count(f) from Generation g join g.favouriteFilenames f "
            + "where g.status = org.hexa.app.generation.domain.GenerationStatus.SUCCEEDED "
            + "and (:tag member of g.tags or exists (select 1 from g.fileTags ft where ft.filename = f and ft.tag = :tag))")
    Page<GalleryItem> findFavouriteItemsByTag(@Param("tag") String tag, Pageable pageable);

    /** Tag utente distinti delle generazioni intere (suggerimenti dei campi tag). */
    @Query("select distinct t from Generation g join g.tags t")
    List<String> findDistinctGenerationTags();

    /** Tag utente distinti dei file. */
    @Query("select distinct ft.tag from Generation g join g.fileTags ft")
    List<String> findDistinctFileTags();

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

    /** Costo stimato totale delle generazioni create da {@code since}: base della stima del credito residuo (feature credits). */
    @Query("select coalesce(sum(g.costUsd), 0) from Generation g where g.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") Instant since);

    /** Galleria contestuale di /deep-chat: le generazioni riuscite di una conversazione, in ordine cronologico. */
    List<Generation> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, GenerationStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Generation g set g.conversationId = null where g.conversationId = :conversationId")
    int clearConversation(@Param("conversationId") Long conversationId);
}
