package org.dual.hexa.core.config.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.domain.FormState;

/** Cio' che il template della sezione di un modulo deve mostrare: campi, stato del form (valori correnti o inviati, dopo un rifiuto) ed esito. */
record SettingsView(String id, String titleKey, List<ConfigField> fields, FormState state, String fragment, String error, boolean saved) {
}
