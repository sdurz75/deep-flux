package org.dual.hexa.core.config.domain;

import java.util.List;

/**
 * Una chiave di configurazione dichiarata da un modulo. {@code key} e' il nome nel form e nella tabella (e, senza override in DB, la property
 * {@code app.<modulo>.<key>}); {@code labelKey}/{@code helpKey} sono chiavi del bundle del MODULO (il core non conosce i suoi testi). {@code options}
 * serve solo a {@code SELECT}; {@code min}/{@code max} solo a {@code INT}.
 *
 * <p>Attributi facoltativi: {@code envVar} = variabile d'ambiente letta dopo la property (es. {@code HX_OAUTH2_ENABLED}); {@code validation} = pattern (e
 * chiave del messaggio) di {@code TEXT} e di ogni voce di {@code LIST}; {@code lowercase}/{@code additive} solo per {@code LIST} (additiva: property e
 * ambiente si SOMMANO alle voci salvate invece di essere sostituite); {@code secretType} per {@code SECRET} e per le colonne {@code SECRET} di una
 * {@code COLLECTION} (un tipo di segreto registrato dal modulo, di norma {@code managed}); {@code columns}, {@code idColumn} (la colonna che identifica
 * la riga: minuscolo, cifre e trattini, al massimo {@value #MAX_ID} caratteri) e {@code maxRows} solo per {@code COLLECTION}.
 */
public record ConfigField(String key, Type type, String defaultValue, String labelKey, String helpKey, List<String> options, int min, int max,
                          String envVar, String validationPattern, String validationMessageKey, boolean lowercase, boolean additive,
                          String secretType, List<Column> columns, String idColumn, int maxRows) {

    public static final int MAX_ID = 20;

    public enum Type { TEXT, INT, BOOL, COLOR, SELECT, SECRET, LIST, COLLECTION }

    public enum ColumnType { TEXT, SELECT, SECRET }

    /** Colonna di una {@code COLLECTION}: {@code required} = non vuota (per un {@code SECRET}: richiesta solo in una riga nuova). */
    public record Column(String key, ColumnType type, String labelKey, List<String> options, String pattern, String patternMessageKey, boolean required) {

        public Column {
            options = options == null ? List.of() : List.copyOf(options);
            if (key == null || !key.matches("[a-z][a-zA-Z0-9-]*")) {
                throw new IllegalArgumentException("Colonna non valida: " + key);
            }
        }

        public static Column text(String key, String labelKey, String pattern, String patternMessageKey, boolean required) {
            return new Column(key, ColumnType.TEXT, labelKey, null, pattern, patternMessageKey, required);
        }

        public static Column select(String key, String labelKey, List<String> options) {
            return new Column(key, ColumnType.SELECT, labelKey, options, null, null, true);
        }

        public static Column secret(String key, String labelKey) {
            return new Column(key, ColumnType.SECRET, labelKey, null, null, null, true);
        }
    }

    public ConfigField {
        options = options == null ? List.of() : List.copyOf(options);
        columns = columns == null ? List.of() : List.copyOf(columns);
        helpKey = helpKey == null || helpKey.isBlank() ? null : helpKey;
        envVar = envVar == null || envVar.isBlank() ? null : envVar;
        if (key == null || !key.matches("[a-z][a-zA-Z0-9-]*")) {
            throw new IllegalArgumentException("Chiave di configurazione non valida: " + key);
        }
        if (type == Type.SECRET && (secretType == null || secretType.isBlank())) {
            throw new IllegalArgumentException("Un campo SECRET richiede il tipo di segreto: " + key);
        }
        if (type == Type.COLLECTION) {
            if (columns.isEmpty() || columns.stream().noneMatch(c -> c.key().equals(idColumn))) {
                throw new IllegalArgumentException("Una COLLECTION richiede colonne e una colonna id fra di esse: " + key);
            }
            if (columns.stream().anyMatch(c -> c.type() == ColumnType.SECRET) && (secretType == null || secretType.isBlank())) {
                throw new IllegalArgumentException("Una COLLECTION con colonne SECRET richiede il tipo di segreto: " + key);
            }
            if (columns.stream().filter(c -> c.key().equals(idColumn)).anyMatch(c -> c.type() != ColumnType.TEXT)) {
                throw new IllegalArgumentException("La colonna id deve essere TEXT: " + key);
            }
        }
    }

    /** Costruttore dei tipi semplici (senza attributi facoltativi). */
    public ConfigField(String key, Type type, String defaultValue, String labelKey, String helpKey, List<String> options, int min, int max) {
        this(key, type, defaultValue, labelKey, helpKey, options, min, max, null, null, null, false, false, null, null, null, 0);
    }

    public static ConfigField text(String key, String defaultValue, String labelKey, String helpKey) {
        return new ConfigField(key, Type.TEXT, defaultValue, labelKey, helpKey, null, 0, 0);
    }

    public static ConfigField integer(String key, int defaultValue, int min, int max, String labelKey, String helpKey) {
        return new ConfigField(key, Type.INT, Integer.toString(defaultValue), labelKey, helpKey, null, min, max);
    }

    public static ConfigField bool(String key, boolean defaultValue, String labelKey, String helpKey) {
        return new ConfigField(key, Type.BOOL, Boolean.toString(defaultValue), labelKey, helpKey, null, 0, 0);
    }

    /** Colore esadecimale {@code #rgb} o {@code #rrggbb}. */
    public static ConfigField color(String key, String defaultValue, String labelKey, String helpKey) {
        return new ConfigField(key, Type.COLOR, defaultValue, labelKey, helpKey, null, 0, 0);
    }

    public static ConfigField select(String key, String defaultValue, List<String> options, String labelKey, String helpKey) {
        return new ConfigField(key, Type.SELECT, defaultValue, labelKey, helpKey, options, 0, 0);
    }

    /** Segreto: salvato cifrato come segreto di {@code secretType} (mai in {@code module_config}), mai riletto dalla UI. */
    public static ConfigField secret(String key, String secretType, String labelKey, String helpKey) {
        return new ConfigField(key, Type.SECRET, "", labelKey, helpKey, null, 0, 0, null, null, null, false, false, secretType, null, null, 0);
    }

    /** Elenco di stringhe, una per riga nella UI. {@code envVar}, {@code validation*} possono essere null. */
    public static ConfigField list(String key, String labelKey, String helpKey, String envVar, String validationPattern, String validationMessageKey,
                                   boolean lowercase, boolean additive) {
        return new ConfigField(key, Type.LIST, "", labelKey, helpKey, null, 0, 0, envVar, validationPattern, validationMessageKey, lowercase, additive, null,
                null, null, 0);
    }

    /** Elenco di record (righe con {@code columns}); {@code secretType} serve alle colonne {@code SECRET}. */
    public static ConfigField collection(String key, String labelKey, String helpKey, List<Column> columns, String idColumn, int maxRows, String secretType) {
        return new ConfigField(key, Type.COLLECTION, "", labelKey, helpKey, null, 0, 0, null, null, null, false, false, secretType, columns, idColumn, maxRows);
    }

    /** Lo stesso campo con una variabile d'ambiente di alias. */
    public ConfigField withEnvVar(String variable) {
        return new ConfigField(key, type, defaultValue, labelKey, helpKey, options, min, max, variable, validationPattern, validationMessageKey, lowercase,
                additive, secretType, columns, idColumn, maxRows);
    }

    /** Il nome del segreto di questo campo ({@code rowId}/{@code column} null per un {@code SECRET} semplice). */
    public static String secretName(String moduleId, String key, String rowId, String column) {
        return rowId == null ? moduleId + "/" + key : moduleId + "/" + key + "/" + rowId + "/" + column;
    }
}
