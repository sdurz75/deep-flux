package org.hexa.app.generation.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.hexa.app.generation.domain.LoraPreset;
import org.hexa.app.generation.port.out.ILoraPresetStore;
import org.springframework.stereotype.Component;

/** {@link ILoraPresetStore} su Spring Data JPA (tabella {@code lora_preset}). */
@Component
class JpaLoraPresetStore implements ILoraPresetStore {

    private final LoraPresetRepository repository;

    JpaLoraPresetStore(LoraPresetRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<LoraPreset> findAllOrderedByName() {
        return repository.findAllByOrderByNameAsc();
    }

    @Override
    public Optional<LoraPreset> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public LoraPreset save(LoraPreset preset) {
        return repository.save(preset);
    }

    @Override
    public void delete(LoraPreset preset) {
        repository.delete(preset);
    }

    @Override
    public long count() {
        return repository.count();
    }

    @Override
    public void deleteAll() {
        repository.deleteAll();
    }

    @Override
    public boolean existsByNameIgnoreCase(String name) {
        return repository.existsByNameIgnoreCase(name);
    }

    @Override
    public boolean existsByNameIgnoreCaseAndIdNot(String name, Long id) {
        return repository.existsByNameIgnoreCaseAndIdNot(name, id);
    }
}
