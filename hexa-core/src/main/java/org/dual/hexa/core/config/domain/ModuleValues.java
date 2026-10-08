package org.dual.hexa.core.config.domain;

import java.util.function.Function;

/**
 * I valori EFFETTIVI di un modulo, risolti a ogni lettura (override in DB, poi property {@code app.<modulo>.<key>}, poi default del campo): un modulo
 * che li tiene qui, e non in un campo, vede le modifiche fatte da {@code /settings} senza riavvio. Un valore illeggibile (es. un intero rovinato a
 * mano nel DB) ricade sul default del campo.
 */
public final class ModuleValues {

    private final Function<String, String> raw;
    private final Function<String, ConfigField> fields;

    public ModuleValues(Function<String, String> raw, Function<String, ConfigField> fields) {
        this.raw = raw;
        this.fields = fields;
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
}
