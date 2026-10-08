package org.dual.hexa.core.config.domain;

import java.util.List;

/**
 * Una chiave di configurazione dichiarata da un modulo. {@code key} e' il nome nel form e nella tabella (e, senza override in DB, la property
 * {@code app.<modulo>.<key>}); {@code labelKey}/{@code helpKey} sono chiavi del bundle del MODULO (il core non conosce i suoi testi). {@code options}
 * serve solo a {@code SELECT}; {@code min}/{@code max} solo a {@code INT}.
 */
public record ConfigField(String key, Type type, String defaultValue, String labelKey, String helpKey, List<String> options, int min, int max) {

    public enum Type { TEXT, INT, BOOL, COLOR, SELECT }

    public ConfigField {
        options = options == null ? List.of() : List.copyOf(options);
        helpKey = helpKey == null || helpKey.isBlank() ? null : helpKey;
        if (key == null || !key.matches("[a-z][a-zA-Z0-9-]*")) {
            throw new IllegalArgumentException("Chiave di configurazione non valida: " + key);
        }
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
}
