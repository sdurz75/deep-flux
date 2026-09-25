package org.dual.replicate.repository;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRepository extends JpaRepository<Generation, Long> {

    /** Usata dalla galleria: solo le generazioni completate, piu' recenti prima. */
    Page<Generation> findByStatusOrderByCreatedAtDesc(GenerationStatus status, Pageable pageable);

    /**
     * Overload di {@code delete(Generation)} che accetta l'id: solo un
     * alias di {@link #deleteById(Object)} con un nome simmetrico a
     * {@code delete(Generation)}. Usata da {@code GenerationService.delete},
     * che ha gia' caricato l'entity per cancellare il file immagine e
     * qui elimina la riga senza doverla ripassare per intero.
     */
    default void delete(Long id) {
        deleteById(id);
    }
}
