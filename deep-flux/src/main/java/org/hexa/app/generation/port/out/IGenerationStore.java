package org.hexa.app.generation.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.hexa.app.generation.domain.AnalysisStatus;
import org.hexa.app.generation.domain.GalleryItem;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.GenerationKind;
import org.hexa.app.generation.domain.GenerationOrigin;
import org.hexa.app.generation.domain.GenerationStatus;
import org.hexa.core.kernel.Paged;

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

    /**
     * Generazioni RIUSCITE filtrate per tipo di media e/o origine ({@code null} = nessun filtro), piu' recenti prima: la tab "Importate"
     * della galleria e il selettore dell'archivio (solo immagini).
     */
    Paged<Generation> pageSucceeded(GenerationKind kind, GenerationOrigin origin, int pageIndex, int pageSize);

    /** Le importate in un dato stato di analisi (recupero all'avvio). */
    List<Generation> findByAnalysisStatus(AnalysisStatus status);

    /** Le importate in un dato stato di analisi create prima di {@code before} (sweep periodico: niente corsa con un'analisi appena partita). */
    List<Generation> findByAnalysisStatusAndCreatedAtBefore(AnalysisStatus status, Instant before);

    /** Tab "Preferiti" della galleria: un item per ogni file con la star, di generazioni SUCCEEDED, piu' recenti prima. */
    Paged<GalleryItem> pageFavouriteItems(int pageIndex, int pageSize);

    /** Generazioni RIUSCITE con il tag utente (sulla generazione o su un suo file), opzionalmente solo le importate, piu' recenti prima. */
    Paged<Generation> pageSucceededByTag(String tag, boolean importedOnly, int pageIndex, int pageSize);

    /** Come {@link #pageFavouriteItems} ma solo i file con il tag (proprio o della generazione). */
    Paged<GalleryItem> pageFavouriteItemsByTag(String tag, int pageIndex, int pageSize);

    /** Tutti i tag utente (di generazione e di file) senza doppioni, in ordine alfabetico. */
    List<String> distinctTags();

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

    /** Somma del costo stimato ({@code cost_usd}) delle generazioni create da {@code since} in poi; 0 se non ce n'e'. */
    BigDecimal sumCostSince(Instant since);

    /** Galleria contestuale: le generazioni di uno stato di una conversazione, in ordine cronologico. */
    List<Generation> findByConversationIdAndStatusOrderByIdAsc(Long conversationId, GenerationStatus status);

    /** Una pagina delle generazioni di uno stato di una conversazione, piu' recenti prima. */
    Paged<Generation> pageByConversationAndStatus(Long conversationId, GenerationStatus status, int pageIndex, int pageSize);

    /** Azzera {@code conversationId} di tutte le generazioni della conversazione. */
    void clearConversation(Long conversationId);
}
