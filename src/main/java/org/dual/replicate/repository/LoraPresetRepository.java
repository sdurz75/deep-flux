package org.dual.replicate.repository;

import java.util.List;

import org.dual.replicate.domain.LoraPreset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoraPresetRepository extends JpaRepository<LoraPreset, Long> {

    List<LoraPreset> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
