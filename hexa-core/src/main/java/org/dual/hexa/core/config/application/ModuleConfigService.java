package org.dual.hexa.core.config.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.dual.hexa.core.config.domain.ConfigChange;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.FormState;
import org.dual.hexa.core.config.domain.ModuleConfigChangedEvent;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.in.IModuleSettings;
import org.dual.hexa.core.config.port.out.IModuleConfigStore;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registro dei moduli ({@code IConfigModule}: ogni bean si autoregistra) e risoluzione dei valori: override in DB, poi la property
 * {@code app.<modulo>.<chiave>} (cosi' chi configurava gia' via yml/env non perde nulla), poi la variabile d'ambiente del campo, poi il default. Gli
 * override sono in cache per modulo, invalidata a ogni salvataggio.
 *
 * <p>Tipi composti: una {@code LIST} sta in {@code module_config} come righe separate da {@code \n}; una {@code COLLECTION} come array JSON SENZA segreti;
 * ogni {@code SECRET} (anche di una riga) e' un segreto {@code ISecrets} del tipo del campo, di nome {@code <modulo>/<chiave>} o
 * {@code <modulo>/<chiave>/<idRiga>/<colonna>}: mai in {@code module_config}.
 */
@Service
class ModuleConfigService implements IModuleSettings {

    private static final Pattern COLOR = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern ROW_ID = Pattern.compile("[a-z][a-z0-9-]*");
    private static final String NEW_ROW = "__new";
    private static final TypeReference<List<Map<String, String>>> ROWS = new TypeReference<>() { };

    private final Map<String, IConfigModule> modules = new LinkedHashMap<>();
    private final IModuleConfigStore store;
    private final Environment environment;
    private final Messages messages;
    private final ApplicationEventPublisher events;
    private final ISecrets secrets;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    ModuleConfigService(ObjectProvider<IConfigModule> providers, IModuleConfigStore store, Environment environment, Messages messages,
                        ApplicationEventPublisher events, ISecrets secrets) {
        this.store = store;
        this.environment = environment;
        this.messages = messages;
        this.events = events;
        this.secrets = secrets;
        providers.orderedStream().forEach(module -> {
            if (!ID.matcher(module.id()).matches()) {
                throw new IllegalStateException("Id di modulo di configurazione non valido: " + module.id());
            }
            if (modules.putIfAbsent(module.id(), module) != null) {
                throw new IllegalStateException("Due moduli di configurazione con lo stesso id: " + module.id());
            }
            module.fields().forEach(field -> checkSecretNames(module.id(), field));
        });
    }

    /** I nomi dei segreti di un modulo devono stare nel limite dei nomi di {@code ISecrets}: si scopre all'avvio, non al primo salvataggio. */
    private static void checkSecretNames(String moduleId, ConfigField field) {
        int longest = switch (field.type()) {
            case SECRET -> ConfigField.secretName(moduleId, field.key(), null, null).length();
            case COLLECTION -> field.columns().stream().filter(c -> c.type() == ConfigField.ColumnType.SECRET)
                    .mapToInt(c -> ConfigField.secretName(moduleId, field.key(), "x".repeat(ConfigField.MAX_ID), c.key()).length()).max().orElse(0);
            default -> 0;
        };
        if (longest > ISecrets.MAX_NAME) {
            throw new IllegalStateException("Nome di segreto troppo lungo (" + longest + " > " + ISecrets.MAX_NAME + ") per " + moduleId + "." + field.key());
        }
    }

    @Override
    public List<IConfigModule> modules() {
        return List.copyOf(modules.values());
    }

    @Override
    public Optional<IConfigModule> module(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    // --- lettura -------------------------------------------------------------------------------------------------------

    @Override
    public ModuleValues values(String moduleId) {
        return valuesOf(require(moduleId), null);
    }

    /** I valori effettivi, con sopra (se non null) una proposta non ancora scritta. */
    private ModuleValues valuesOf(IConfigModule module, Proposal proposal) {
        Map<String, ConfigField> fields = byKey(module);
        String moduleId = module.id();
        ModuleValues.Extras extras = new ModuleValues.Extras() {
            @Override
            public List<String> list(String key) {
                ConfigField field = field(fields, moduleId, key, ConfigField.Type.LIST);
                if (proposal != null && proposal.stored.containsKey(key)) {
                    return effectiveList(moduleId, field, proposal.stored.get(key));
                }
                return effectiveList(moduleId, field, storedRaw(moduleId, key));
            }

            @Override
            public Optional<String> secret(String key, String rowId, String column) {
                return secretValue(moduleId, fields, proposal, key, rowId, column);
            }

            @Override
            public Optional<String> secretHint(String key, String rowId, String column) {
                return secret(key, rowId, column).map(ModuleConfigService::hint);
            }

            @Override
            public List<ModuleValues.RowData> rows(String key) {
                ConfigField field = field(fields, moduleId, key, ConfigField.Type.COLLECTION);
                if (proposal != null && proposal.rows.containsKey(key)) {
                    return proposal.rows.get(key);
                }
                return parseRows(field, storedRaw(moduleId, key));
            }
        };
        return new ModuleValues(key -> proposal != null && proposal.stored.containsKey(key) ? proposal.stored.get(key) : resolve(moduleId, fields.get(key), key),
                key -> field(fields, moduleId, key, null), extras);
    }

    private static ConfigField field(Map<String, ConfigField> fields, String moduleId, String key, ConfigField.Type expected) {
        ConfigField field = fields.get(key);
        if (field == null) {
            throw new IllegalArgumentException("Chiave sconosciuta nel modulo " + moduleId + ": " + key);
        }
        if (expected != null && field.type() != expected) {
            throw new IllegalArgumentException("La chiave " + moduleId + "." + key + " non e' di tipo " + expected);
        }
        return field;
    }

    private Optional<String> secretValue(String moduleId, Map<String, ConfigField> fields, Proposal proposal, String key, String rowId, String column) {
        ConfigField field = field(fields, moduleId, key, null);
        String name = ConfigField.secretName(moduleId, key, rowId, column);
        if (proposal != null) {
            if (proposal.secrets.containsKey(name)) {
                return Optional.of(proposal.secrets.get(name));
            }
            if (proposal.deletedSecrets.stream().anyMatch(d -> d.endsWith("/") ? name.startsWith(d) : name.equals(d))) {
                return Optional.empty();
            }
        }
        try {
            return secrets.resolveByName(field.secretType(), name);
        } catch (RuntimeException e) {
            // chiave di cifratura mancante/diversa: il segreto non e' leggibile, quindi non e' "impostato" per chi lo usa
            return Optional.empty();
        }
    }

    private String storedRaw(String moduleId, String key) {
        return cache.computeIfAbsent(moduleId, store::load).get(key);
    }

    private String resolve(String moduleId, ConfigField field, String key) {
        String override = storedRaw(moduleId, key);
        if (override != null) {
            return override;
        }
        String property = environment.getProperty("app." + moduleId + "." + key);
        if (property != null) {
            return property;
        }
        return field == null || field.envVar() == null ? null : environment.getProperty(field.envVar());
    }

    /** Le voci effettive di una lista: salvate (o sostituite da property/ambiente/default) oppure, se additiva, la somma. */
    private List<String> effectiveList(String moduleId, ConfigField field, String stored) {
        List<String> property = splitList(environment.getProperty("app." + moduleId + "." + field.key()), field);
        List<String> env = field.envVar() == null ? List.of() : splitList(environment.getProperty(field.envVar()), field);
        if (field.additive()) {
            Set<String> all = new LinkedHashSet<>(splitList(stored, field));
            all.addAll(property);
            all.addAll(env);
            return List.copyOf(all);
        }
        if (stored != null) {
            return splitList(stored, field);
        }
        if (!property.isEmpty()) {
            return property;
        }
        if (!env.isEmpty()) {
            return env;
        }
        return splitList(field.defaultValue(), field);
    }

    /** Voci separate da a-capo o virgola, normalizzate (trim, minuscolo se richiesto), senza vuoti ne' doppioni. */
    private static List<String> splitList(String raw, ConfigField field) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split("[\\n\\r,]")).map(String::trim).map(v -> field.lowercase() ? v.toLowerCase() : v)
                .filter(v -> !v.isEmpty()).distinct().toList();
    }

    private List<ModuleValues.RowData> parseRows(ConfigField field, String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> parsed = json.readValue(raw, ROWS);
            List<ModuleValues.RowData> rows = new ArrayList<>();
            for (Map<String, String> row : parsed) {
                String id = row.get(field.idColumn());
                if (id != null && !id.isBlank()) {
                    rows.add(new ModuleValues.RowData(id, row));
                }
            }
            return rows;
        } catch (JacksonException e) {
            return List.of(); // JSON rovinato a mano nel DB: come "nessuna riga"
        }
    }

    // --- stato per la UI -----------------------------------------------------------------------------------------------

    @Override
    public FormState formState(String moduleId) {
        return state(require(moduleId), null);
    }

    @Override
    public FormState preview(String moduleId, Map<String, String> submitted) {
        return state(require(moduleId), submitted);
    }

    private FormState state(IConfigModule module, Map<String, String> form) {
        String moduleId = module.id();
        ModuleValues values = values(moduleId);
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, String> hints = new LinkedHashMap<>();
        Map<String, List<String>> environmentEntries = new LinkedHashMap<>();
        Map<String, List<FormState.Row>> rows = new LinkedHashMap<>();
        for (ConfigField field : module.fields()) {
            String key = field.key();
            switch (field.type()) {
                case SECRET -> hints.put(key, values.getSecretHint(key).orElse(""));
                case LIST -> {
                    String stored = storedRaw(moduleId, key);
                    List<String> shown = field.additive() ? splitList(stored, field) : values.getList(key);
                    texts.put(key, form != null && form.containsKey(key) ? form.get(key) : String.join("\n", shown));
                    if (field.additive()) {
                        List<String> readOnly = new ArrayList<>(values.getList(key));
                        readOnly.removeAll(splitList(stored, field));
                        environmentEntries.put(key, readOnly);
                    }
                }
                case COLLECTION -> rows.put(key, rowsState(moduleId, field, values, form));
                case BOOL -> texts.put(key, form != null ? Boolean.toString(form.containsKey(key)) : values.getString(key));
                default -> texts.put(key, form != null && form.containsKey(key) ? form.get(key) : values.getString(key));
            }
        }
        return new FormState(texts, hints, environmentEntries, rows);
    }

    private List<FormState.Row> rowsState(String moduleId, ConfigField field, ModuleValues values, Map<String, String> form) {
        List<FormState.Row> result = new ArrayList<>();
        Map<String, Map<String, String>> submitted = form == null ? Map.of() : groupRows(field, form);
        for (ModuleValues.Row row : values.getRows(field.key())) {
            Map<String, String> shown = new LinkedHashMap<>();
            Map<String, String> secretHints = new LinkedHashMap<>();
            Map<String, String> sent = submitted.getOrDefault(row.id(), Map.of());
            for (ConfigField.Column column : field.columns()) {
                if (column.type() == ConfigField.ColumnType.SECRET) {
                    secretHints.put(column.key(), row.secret(column.key()).map(ModuleConfigService::hint).orElse(""));
                } else {
                    shown.put(column.key(), sent.containsKey(column.key()) ? sent.get(column.key()) : row.get(column.key()));
                }
            }
            result.add(new FormState.Row(row.id(), shown, secretHints, sent.containsKey("__remove")));
        }
        Map<String, String> fresh = submitted.get(NEW_ROW);
        if (fresh != null) {
            Map<String, String> shown = new LinkedHashMap<>();
            field.columns().stream().filter(c -> c.type() != ConfigField.ColumnType.SECRET).forEach(c -> shown.put(c.key(), fresh.getOrDefault(c.key(), "")));
            result.add(new FormState.Row("", shown, Map.of(), false));
        }
        return result;
    }

    /** I parametri {@code <chiave>.<idRiga>.<colonna>} del form di una {@code COLLECTION}, raggruppati per riga. */
    private static Map<String, Map<String, String>> groupRows(ConfigField field, Map<String, String> form) {
        String prefix = field.key() + ".";
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        form.forEach((name, value) -> {
            if (!name.startsWith(prefix)) {
                return;
            }
            String rest = name.substring(prefix.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                return;
            }
            grouped.computeIfAbsent(rest.substring(0, dot), id -> new LinkedHashMap<>()).put(rest.substring(dot + 1), value == null ? "" : value);
        });
        return grouped;
    }

    // --- scrittura -----------------------------------------------------------------------------------------------------

    /** La proposta di un salvataggio: cosa scrivere in {@code module_config} (valori grezzi) e nei segreti, e cosa cancellare. */
    private static final class Proposal {
        final Map<String, String> stored = new LinkedHashMap<>();
        final Map<String, List<ModuleValues.RowData>> rows = new HashMap<>();
        final Map<String, String> secrets = new LinkedHashMap<>();
        final Map<String, String> secretTypes = new HashMap<>();
        final Set<String> deletedSecrets = new LinkedHashSet<>();
        final Map<String, String> deletedSecretTypes = new HashMap<>();
    }

    @Override
    @Transactional
    public void save(String moduleId, Map<String, String> submitted) {
        IConfigModule module = require(moduleId);
        Proposal proposal = new Proposal();
        for (ConfigField field : module.fields()) {
            String raw = submitted.get(field.key());
            switch (field.type()) {
                case BOOL -> proposal.stored.put(field.key(), Boolean.toString(raw != null && Boolean.parseBoolean(raw.trim())));
                case SECRET -> {
                    if (raw != null && !raw.isBlank()) {
                        proposal.secrets.put(ConfigField.secretName(moduleId, field.key(), null, null), validSecret(field, raw.strip()));
                        proposal.secretTypes.put(ConfigField.secretName(moduleId, field.key(), null, null), field.secretType());
                    }
                }
                case LIST -> {
                    if (raw != null) {
                        proposal.stored.put(field.key(), String.join("\n", validList(field, raw)));
                    }
                }
                case COLLECTION -> collect(moduleId, field, groupRows(field, submitted), proposal);
                default -> {
                    if (raw != null) {
                        proposal.stored.put(field.key(), validate(field, raw.trim()));
                    }
                }
            }
        }
        if (!proposal.secrets.isEmpty() && !secrets.isConfigured()) {
            throw new ConfigException(messages.get("settings.error.secretsUnavailable"));
        }
        module.validate(new ConfigChange(valuesOf(module, null), valuesOf(module, proposal)));

        store.saveAll(moduleId, proposal.stored);
        proposal.deletedSecrets.forEach(prefixOrName -> {
            String type = proposal.deletedSecretTypes.get(prefixOrName);
            if (prefixOrName.endsWith("/")) {
                secrets.deleteByNamePrefix(type, prefixOrName);
            } else {
                secrets.deleteByName(type, prefixOrName);
            }
        });
        proposal.secrets.forEach((name, value) -> secrets.store(proposal.secretTypes.get(name), name, value));
        Set<String> changed = new LinkedHashSet<>(proposal.stored.keySet());
        module.fields().stream().filter(f -> f.type() == ConfigField.Type.SECRET && proposal.secrets.containsKey(ConfigField.secretName(moduleId, f.key(), null, null)))
                .forEach(f -> changed.add(f.key()));
        changed(moduleId, changed);
    }

    @Override
    @Transactional
    public void reset(String moduleId) {
        IConfigModule module = require(moduleId);
        store.deleteAll(moduleId);
        for (ConfigField field : module.fields()) {
            if (field.type() == ConfigField.Type.SECRET) {
                secrets.deleteByName(field.secretType(), ConfigField.secretName(moduleId, field.key(), null, null));
            } else if (field.type() == ConfigField.Type.COLLECTION && field.secretType() != null) {
                secrets.deleteByNamePrefix(field.secretType(), moduleId + "/" + field.key() + "/");
            }
        }
        changed(moduleId, byKey(module).keySet());
    }

    /**
     * Svuota la cache e avvisa chi ascolta, DOPO il commit: prima, un'altra richiesta potrebbe rileggere i valori vecchi (non ancora visibili) e tenerli in
     * cache; per chi decide un accesso (il cancello di hexa-oauth2) sarebbe un valore sbagliato fino al prossimo salvataggio.
     */
    private void changed(String moduleId, Set<String> keys) {
        Runnable done = () -> {
            cache.remove(moduleId);
            events.publishEvent(new ModuleConfigChangedEvent(moduleId, keys));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    done.run();
                }
            });
        } else {
            done.run();
        }
    }

    private String validSecret(ConfigField field, String value) {
        if (value.length() > ISecrets.MAX_VALUE) {
            throw new ConfigException(messages.get("settings.error.length", label(field.labelKey())));
        }
        return value;
    }

    /** Le voci di una textarea (una per riga), normalizzate e validate. */
    private List<String> validList(ConfigField field, String raw) {
        String label = label(field.labelKey());
        List<String> entries = Arrays.stream(raw.split("[\\n\\r]")).map(String::trim).map(v -> field.lowercase() ? v.toLowerCase() : v)
                .filter(v -> !v.isEmpty()).distinct().toList();
        for (String entry : entries) {
            if (entry.length() > 200) {
                throw new ConfigException(messages.get("settings.error.length", label));
            }
            if (field.validationPattern() != null && !entry.matches(field.validationPattern())) {
                throw new ConfigException(messages.get(field.validationMessageKey() == null ? "settings.error.pattern" : field.validationMessageKey(), label, entry));
            }
        }
        return entries;
    }

    /** Le righe di una {@code COLLECTION}: aggiorna quelle inviate, toglie quelle marcate, aggiunge la nuova; il resto resta com'e'. */
    private void collect(String moduleId, ConfigField field, Map<String, Map<String, String>> grouped, Proposal proposal) {
        if (grouped.isEmpty()) {
            return;
        }
        String label = label(field.labelKey());
        Map<String, Map<String, String>> byId = new LinkedHashMap<>();
        for (ModuleValues.RowData row : parseRows(field, storedRaw(moduleId, field.key()))) {
            byId.put(row.id(), new LinkedHashMap<>(row.values()));
        }
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            String rowId = entry.getKey();
            Map<String, String> sent = entry.getValue();
            if (NEW_ROW.equals(rowId)) {
                continue;
            }
            if (!byId.containsKey(rowId)) {
                continue; // form vecchio: la riga e' gia' stata tolta
            }
            if (sent.containsKey("__remove")) {
                byId.remove(rowId);
                String prefix = moduleId + "/" + field.key() + "/" + rowId + "/";
                if (field.secretType() != null) {
                    proposal.deletedSecrets.add(prefix);
                    proposal.deletedSecretTypes.put(prefix, field.secretType());
                }
                continue;
            }
            Map<String, String> row = byId.get(rowId);
            for (ConfigField.Column column : field.columns()) {
                String value = sent.get(column.key());
                if (column.key().equals(field.idColumn()) || value == null) {
                    continue;
                }
                if (column.type() == ConfigField.ColumnType.SECRET) {
                    if (!value.isBlank()) {
                        putSecret(moduleId, field, rowId, column, value.strip(), proposal);
                    }
                } else {
                    row.put(column.key(), validColumn(column, value.trim()));
                }
            }
        }
        Map<String, String> fresh = grouped.get(NEW_ROW);
        if (fresh != null && isFilled(field, fresh)) {
            String id = fresh.getOrDefault(field.idColumn(), "").trim();
            if (id.length() > ConfigField.MAX_ID || !ROW_ID.matcher(id).matches()) {
                throw new ConfigException(messages.get("settings.error.rowId", label, ConfigField.MAX_ID));
            }
            if (byId.containsKey(id)) {
                throw new ConfigException(messages.get("settings.error.rowDuplicate", label, id));
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put(field.idColumn(), id);
            for (ConfigField.Column column : field.columns()) {
                if (column.key().equals(field.idColumn())) {
                    continue;
                }
                String value = fresh.getOrDefault(column.key(), "").trim();
                if (column.type() == ConfigField.ColumnType.SECRET) {
                    if (value.isEmpty()) {
                        throw new ConfigException(messages.get("settings.error.required", label(column.labelKey())));
                    }
                    putSecret(moduleId, field, id, column, fresh.get(column.key()).strip(), proposal);
                } else {
                    row.put(column.key(), validColumn(column, value));
                }
            }
            byId.put(id, row);
        }
        if (byId.size() > field.maxRows()) {
            throw new ConfigException(messages.get("settings.error.rows", label, field.maxRows()));
        }
        List<ModuleValues.RowData> result = byId.entrySet().stream().map(e -> new ModuleValues.RowData(e.getKey(), e.getValue())).toList();
        proposal.rows.put(field.key(), result);
        try {
            proposal.stored.put(field.key(), json.writeValueAsString(result.stream().map(ModuleValues.RowData::values).toList()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Serializzazione delle righe di " + field.key(), e);
        }
    }

    private void putSecret(String moduleId, ConfigField field, String rowId, ConfigField.Column column, String value, Proposal proposal) {
        String name = ConfigField.secretName(moduleId, field.key(), rowId, column.key());
        proposal.secrets.put(name, validSecret(field, value));
        proposal.secretTypes.put(name, field.secretType());
    }

    /** Una riga nuova e' "compilata" se almeno un campo di testo o segreto non e' vuoto (le select hanno sempre un valore). */
    private static boolean isFilled(ConfigField field, Map<String, String> row) {
        return field.columns().stream().filter(c -> c.type() != ConfigField.ColumnType.SELECT)
                .anyMatch(c -> !row.getOrDefault(c.key(), "").isBlank());
    }

    private String validColumn(ConfigField.Column column, String value) {
        String label = label(column.labelKey());
        if (value.isEmpty()) {
            if (column.required()) {
                throw new ConfigException(messages.get("settings.error.required", label));
            }
            return value;
        }
        if (value.length() > 500) {
            throw new ConfigException(messages.get("settings.error.length", label));
        }
        if (column.type() == ConfigField.ColumnType.SELECT && !column.options().contains(value)) {
            throw new ConfigException(messages.get("settings.error.option", label));
        }
        if (column.pattern() != null && !value.matches(column.pattern())) {
            throw new ConfigException(messages.get(column.patternMessageKey() == null ? "settings.error.pattern" : column.patternMessageKey(), label, value));
        }
        return value;
    }

    /** Il testo del rifiuto e' risolto qui (nella lingua della richiesta), prima dell'eccezione. */
    private String validate(ConfigField field, String value) {
        String label = label(field.labelKey());
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
            case TEXT -> {
                if (value.length() > 2000) {
                    throw new ConfigException(messages.get("settings.error.length", label));
                }
                if (field.validationPattern() != null && !value.isEmpty() && !value.matches(field.validationPattern())) {
                    throw new ConfigException(messages.get(field.validationMessageKey() == null ? "settings.error.pattern" : field.validationMessageKey(), label, value));
                }
            }
            default -> {
            }
        }
        return value;
    }

    /** L'etichetta di un campo per un messaggio: una chiave mancante nel bundle del modulo non deve diventare un 500. */
    private String label(String key) {
        return messages.getOrDefault(key, key);
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

    private static String hint(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
