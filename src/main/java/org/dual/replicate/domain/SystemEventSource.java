package org.dual.replicate.domain;

/** Da dove viene un evento registrato in {@link SystemEvent}. */
public enum SystemEventSource {
    REPLICATE("Replicate"),
    OPENROUTER("OpenRouter"),
    SEARXNG("SearXNG"),
    STORAGE("Storage"),
    TOKENS("Token"),
    INTERNAL("Interno");

    private final String label;

    SystemEventSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
