package org.dual.replicate.repository;

import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRepository extends JpaRepository<Generation, Long> {

    /** Usata dalla galleria: solo le generazioni completate, piu' recenti prima. */
    Page<Generation> findByStatusOrderByCreatedAtDesc(GenerationStatus status, Pageable pageable);
}
