package org.dual.replicate.app.generation.port.out;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.LoraPreset;

/** Persistenza dei LoRA anagrafati. */
public interface ILoraPresetStore {

    List<LoraPreset> findAllOrderedByName();

    Optional<LoraPreset> findById(Long id);

    LoraPreset save(LoraPreset preset);

    void delete(LoraPreset preset);

    long count();

    /** Svuota la tabella (test e reset). */
    void deleteAll();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
