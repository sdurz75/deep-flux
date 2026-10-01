package org.dual.replicate.app;

import org.dual.replicate.core.kernel.EventSource;

/** Sorgenti di eventi specifiche dell'app (servizi esterni usati dalla generazione e dalla chat). */
public enum AppEventSource implements EventSource {
    REPLICATE,
    OPENROUTER,
    SEARXNG,
    LORAS
}
