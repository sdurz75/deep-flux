package org.dual.hexa.core.config.domain;

import java.util.List;

/**
 * Un gruppo semantico di campi di un modulo ({@code IConfigModule#groups()}): la pagina {@code /settings} lo rende come un riquadro con titolo
 * ({@code titleKey}, bundle del MODULO) e dispone i riquadri in una griglia che si allarga sui display grandi. {@code fieldKeys} sono le chiavi dei
 * campi, nell'ordine in cui compaiono; le chiavi sconosciute si ignorano.
 */
public record ConfigGroup(String titleKey, List<String> fieldKeys) {

    public ConfigGroup {
        fieldKeys = List.copyOf(fieldKeys);
    }

    public static ConfigGroup of(String titleKey, String... fieldKeys) {
        return new ConfigGroup(titleKey, List.of(fieldKeys));
    }
}
