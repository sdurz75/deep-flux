package org.dual.hexa.core.config.domain;

import java.util.Set;

/** Pubblicato dopo un salvataggio (o un ripristino) di un modulo: per chi tiene in cache un valore derivato dalla configurazione. */
public record ModuleConfigChangedEvent(String moduleId, Set<String> keys) {
}
