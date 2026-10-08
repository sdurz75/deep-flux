package org.dual.hexa.core.config.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * I valori EFFETTIVI di un modulo, risolti a ogni lettura (override in DB, poi property {@code app.<modulo>.<key>}, poi la variabile d'ambiente del
 * campo, poi default): un modulo che li tiene qui, e non in un campo, vede le modifiche fatte da {@code /settings} senza riavvio. Un valore illeggibile
 * (es. un intero rovinato a mano nel DB) ricade sul default del campo. Nessun segreto compare in {@code toString}.
 */
public final class ModuleValues {

    /** Le letture dei tipi composti: le fornisce il servizio, che conosce il DB, l'ambiente e i segreti. */
    public interface Extras {

        List<String> list(String key);

        Optional<String> secret(String key, String rowId, String column);

        Optional<String> secretHint(String key, String rowId, String column);

        List<RowData> rows(String key);
    }

    /** Una riga di {@code COLLECTION} senza segreti: id e colonne non segrete. */
    public record RowData(String id, Map<String, String> values) {

        public RowData {
            values = Map.copyOf(values);
        }
    }

    /** Una riga con accesso ai suoi segreti (decifrati solo quando richiesti). */
    public static final class Row {

        private final String key;
        private final RowData data;
        private final Extras extras;

        Row(String key, RowData data, Extras extras) {
            this.key = key;
            this.data = data;
            this.extras = extras;
        }

        public String id() {
            return data.id();
        }

        public String get(String column) {
            return data.values().getOrDefault(column, "");
        }

        public Optional<String> secret(String column) {
            return extras.secret(key, data.id(), column);
        }

        @Override
        public String toString() {
            return "Row[" + key + ":" + data.id() + "]";
        }
    }

    private final Function<String, String> raw;
    private final Function<String, ConfigField> fields;
    private final Extras extras;

    public ModuleValues(Function<String, String> raw, Function<String, ConfigField> fields) {
        this(raw, fields, null);
    }

    public ModuleValues(Function<String, String> raw, Function<String, ConfigField> fields, Extras extras) {
        this.raw = raw;
        this.fields = fields;
        this.extras = extras;
    }

    public String getString(String key) {
        String value = raw.apply(key);
        return value == null ? fields.apply(key).defaultValue() : value;
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(getString(key).trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(fields.apply(key).defaultValue());
        }
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key).trim());
    }

    /** Le voci di un campo {@code LIST} (normalizzate; per una lista additiva anche quelle di property e ambiente). */
    public List<String> getList(String key) {
        return extras().list(key);
    }

    /** Il valore in chiaro di un campo {@code SECRET} (decifrato ora), vuoto se non e' impostato. */
    public Optional<String> getSecret(String key) {
        return extras().secret(key, null, null);
    }

    /** Gli ultimi caratteri di un segreto, per mostrare nella UI che e' impostato. */
    public Optional<String> getSecretHint(String key) {
        return extras().secretHint(key, null, null);
    }

    public List<Row> getRows(String key) {
        return extras().rows(key).stream().map(data -> new Row(key, data, extras())).toList();
    }

    private Extras extras() {
        if (extras == null) {
            throw new UnsupportedOperationException("Valori composti non disponibili");
        }
        return extras;
    }

    @Override
    public String toString() {
        return "ModuleValues[...]";
    }
}
