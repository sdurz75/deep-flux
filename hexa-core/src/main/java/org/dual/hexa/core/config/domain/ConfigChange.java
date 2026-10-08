package org.dual.hexa.core.config.domain;

/** Un salvataggio in corso, per {@code IConfigModule#validate}: i valori attuali e quelli che verrebbero (segreti inclusi, leggibili con {@code getSecret}). */
public record ConfigChange(ModuleValues current, ModuleValues proposed) {
}
