package org.dual.replicate.core.events.domain;

import org.dual.replicate.core.kernel.EventSource;

/** Sorgenti di eventi proprie del core; etichette in {@code events.source.<NAME>} del bundle. */
public enum CoreEventSource implements EventSource {
    STORAGE,
    TOKENS,
    INTERNAL
}
