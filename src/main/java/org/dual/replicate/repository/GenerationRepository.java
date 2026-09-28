package org.dual.replicate.repository;

import java.time.Instant;
import java.util.Collection;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRepository extends JpaRepository<Generation, Long> {

    /** Usata dalla galleria: solo le generazioni completate, piu' recenti prima. */
    Page<Generation> findByStatusOrderByCreatedAtDesc(GenerationStatus status, Pageable pageable);

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
}
