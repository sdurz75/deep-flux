package org.dual.hexa.core.config.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.FormState;

/**
 * Cio' che il template della sezione di un modulo deve mostrare: i campi (raggruppati), lo stato del form (valori correnti o inviati, dopo un
 * rifiuto) ed esito. {@code Group#titleKey} null = riquadro senza titolo.
 */
record SettingsView(String id, String titleKey, List<Group> groups, FormState state, String fragment, String error, boolean saved) {

    /** {@code wide}: il riquadro contiene un elenco di record e occupa tutta la riga della griglia. */
    record Group(String titleKey, List<ConfigField> fields, boolean wide) {

        Group(String titleKey, List<ConfigField> fields) {
            this(titleKey, fields, fields.stream().anyMatch(f -> f.type() == ConfigField.Type.COLLECTION));
        }
    }
}
