package org.dual.hexa.core.config.adapter.in.web;

import java.util.List;
import java.util.Map;
import org.dual.hexa.core.config.domain.ConfigField;

/** Cio' che il template della sezione di un modulo deve mostrare: campi, valori correnti (o inviati, dopo un rifiuto) ed esito. */
record SettingsView(String id, String titleKey, List<ConfigField> fields, Map<String, String> values, String fragment, String error, boolean saved) {
}
