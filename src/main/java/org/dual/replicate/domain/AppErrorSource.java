package org.dual.replicate.domain;

/** Da dove viene un errore registrato in {@link AppError}. */
public enum AppErrorSource {
    REPLICATE("Replicate"),
    OPENROUTER("OpenRouter"),
    SEARXNG("SearXNG"),
    STORAGE("Storage"),
    INTERNAL("Interno");

    private final String label;

    AppErrorSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
