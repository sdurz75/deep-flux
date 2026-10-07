package org.hexa.core.events.domain;

import org.hexa.core.kernel.EventSource;

/** Sorgenti di eventi proprie del core; etichette in {@code events.source.<NAME>} del bundle. */
public enum CoreEventSource implements EventSource {
    STORAGE,
    TOKENS,
    INTERNAL
}
