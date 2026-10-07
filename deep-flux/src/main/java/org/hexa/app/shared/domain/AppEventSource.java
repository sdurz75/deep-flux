package org.hexa.app.shared.domain;

import org.hexa.core.kernel.EventSource;

/** Sorgenti di eventi specifiche dell'app (servizi esterni usati dalla generazione e dal training; OpenRouter e SearXNG sono del core). */
public enum AppEventSource implements EventSource {
    REPLICATE,
    LORAS,
    /** Dataset e training di LoRA: guasti non remoti (file, zip, caption) e rifiuti applicativi. Gli errori del trainer restano {@code REPLICATE}. */
    TRAINING,
    /** La copia dei pesi di un LoRA su HuggingFace (controllo del token e creazione del repo prima del training). */
    HUGGINGFACE
}
