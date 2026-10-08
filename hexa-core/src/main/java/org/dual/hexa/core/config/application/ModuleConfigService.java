package org.dual.hexa.core.config.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.ModuleConfigChangedEvent;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.config.port.out.IModuleConfigStore;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro dei moduli ({@code IConfigModule}: ogni bean si autoregistra) e risoluzione dei valori: override in DB, poi la property
 * {@code app.<modulo>.<chiave>} (cosi' chi configurava gia' via yml/env non perde nulla), poi il default del campo. Gli override sono in cache per
 * modulo, invalidata a ogni salvataggio.
 */
@Service
class ModuleConfigService implements IModuleSettings {

    private static final Pattern COLOR = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");

    private final Map<String, IConfigModule> modules = new LinkedHashMap<>();
    private final IModuleConfigStore store;
    private final Environment environment;
    private final Messages messages;
    private final ApplicationEventPublisher events;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    ModuleConfigService(ObjectProvider<IConfigModule> providers, IModuleConfigStore store, Environment environment, Messages messages,
                        ApplicationEventPublisher events) {
        this.store = store;
        this.environment = environment;
        this.messages = messages;
        this.events = events;
        providers.orderedStream().forEach(module -> {
            if (!ID.matcher(module.id()).matches()) {
                throw new IllegalStateException("Id di modulo di configurazione non valido: " + module.id());
            }
            if (modules.putIfAbsent(module.id(), module) != null) {
                throw new IllegalStateException("Due moduli di configurazione con lo stesso id: " + module.id());
            }
        });
    }

    @Override
    public List<IConfigModule> modules() {
        return List.copyOf(modules.values());
    }

    @Override
    public Optional<IConfigModule> module(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    @Override
    public ModuleValues values(String moduleId) {
        IConfigModule module = require(moduleId);
        Map<String, ConfigField> fields = byKey(module);
        return new ModuleValues(key -> resolve(moduleId, key), key -> {
            ConfigField field = fields.get(key);
            if (field == null) {
                throw new IllegalArgumentException("Chiave sconosciuta nel modulo " + moduleId + ": " + key);
            }
            return field;
        });
    }

    @Override
    @Transactional
    public void save(String moduleId, Map<String, String> submitted) {
        IConfigModule module = require(moduleId);
        Map<String, String> toStore = new LinkedHashMap<>();
        for (ConfigField field : module.fields()) {
            String raw = submitted.get(field.key());
            if (field.type() == ConfigField.Type.BOOL) {
                toStore.put(field.key(), Boolean.toString(raw != null && Boolean.parseBoolean(raw.trim())));
            } else if (raw != null) {
                toStore.put(field.key(), validate(field, raw.trim()));
            }
        }
        store.saveAll(moduleId, toStore);
        cache.remove(moduleId);
        events.publishEvent(new ModuleConfigChangedEvent(moduleId, toStore.keySet()));
    }

    @Override
    @Transactional
    public void reset(String moduleId) {
        IConfigModule module = require(moduleId);
        store.deleteAll(moduleId);
        cache.remove(moduleId);
        events.publishEvent(new ModuleConfigChangedEvent(moduleId, byKey(module).keySet()));
    }

    private String resolve(String moduleId, String key) {
        String override = cache.computeIfAbsent(moduleId, store::load).get(key);
        if (override != null) {
            return override;
        }
        return environment.getProperty("app." + moduleId + "." + key);
    }

    /** Il testo del rifiuto e' risolto qui (nella lingua della richiesta), prima dell'eccezione. */
    private String validate(ConfigField field, String value) {
        String label = messages.get(field.labelKey());
        switch (field.type()) {
            case INT -> {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed < field.min() || parsed > field.max()) {
                        throw new ConfigException(messages.get("settings.error.range", label, field.min(), field.max()));
                    }
                } catch (NumberFormatException e) {
                    throw new ConfigException(messages.get("settings.error.int", label));
                }
            }
            case COLOR -> {
                if (!COLOR.matcher(value).matches()) {
                    throw new ConfigException(messages.get("settings.error.color", label));
                }
            }
            case SELECT -> {
                if (!field.options().contains(value)) {
                    throw new ConfigException(messages.get("settings.error.option", label));
                }
            }
            case TEXT, BOOL -> {
                if (value.length() > 2000) {
                    throw new ConfigException(messages.get("settings.error.length", label));
                }
            }
        }
        return value;
    }

    private IConfigModule require(String moduleId) {
        IConfigModule module = modules.get(moduleId);
        if (module == null) {
            throw new IllegalArgumentException("Modulo di configurazione sconosciuto: " + moduleId);
        }
        return module;
    }

    private static Map<String, ConfigField> byKey(IConfigModule module) {
        Map<String, ConfigField> map = new HashMap<>();
        module.fields().forEach(field -> map.put(field.key(), field));
        return map;
    }
}
