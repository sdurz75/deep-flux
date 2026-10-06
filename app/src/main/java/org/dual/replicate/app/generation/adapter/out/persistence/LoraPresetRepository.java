package org.dual.replicate.app.generation.adapter.out.persistence;

import java.util.List;

import org.dual.replicate.app.generation.domain.LoraPreset;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface LoraPresetRepository extends JpaRepository<LoraPreset, Long> {

    List<LoraPreset> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
