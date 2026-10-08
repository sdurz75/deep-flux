package org.dual.hexa.core.config.adapter.out.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.dual.hexa.core.config.port.out.IModuleConfigStore;
import org.springframework.stereotype.Component;

@Component
class JpaModuleConfigStore implements IModuleConfigStore {

    private final ModuleConfigRepository repository;

    JpaModuleConfigStore(ModuleConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, String> load(String moduleId) {
        Map<String, String> values = new HashMap<>();
        repository.findByModule(moduleId).forEach(entry -> values.put(entry.getConfigKey(), entry.getConfigValue()));
        return values;
    }

    @Override
    public void saveAll(String moduleId, Map<String, String> values) {
        Instant now = Instant.now();
        // save = merge su chiave composta: inserisce o aggiorna la riga (modulo, chiave).
        values.forEach((key, value) -> repository.save(new ModuleConfigEntry(moduleId, key, value, now)));
    }

    @Override
    public void deleteAll(String moduleId) {
        repository.deleteByModule(moduleId);
    }
}
