package org.hexa.core.ai.domain;

import org.hexa.core.kernel.EventSource;

/** Sorgenti di eventi di hexa-ai; etichette in {@code events.source.<NAME>} di {@code messages-ai}. I nomi sono persistiti in {@code system_event}. */
public enum AiEventSource implements EventSource {
    /** Il provider LLM della chat e dell'AI (OpenRouter). */
    OPENROUTER,
    /** La ricerca web dell'assistente (SearXNG). */
    SEARXNG
}
