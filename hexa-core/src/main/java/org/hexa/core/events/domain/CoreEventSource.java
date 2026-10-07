package org.hexa.core.events.domain;

import org.hexa.core.kernel.EventSource;

/** Sorgenti di eventi proprie del core; etichette in {@code events.source.<NAME>} del bundle. */
public enum CoreEventSource implements EventSource {
    STORAGE,
    TOKENS,
    INTERNAL,
    /** Il provider LLM della chat e dell'AI (OpenRouter). */
    OPENROUTER,
    /** La ricerca web dell'assistente (SearXNG). */
    SEARXNG
}
