package org.dual.hexa.app.chat.adapter.ai;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quante volte, in un turno, il modello ha chiesto una vera chiamata al modello di visione (ogni {@code describeImage} su un'immagine
 * non gia' analizzata costa token OpenRouter). Una nuova istanza per turno nel {@code ToolContext}, come {@link GenerationResultHolder}:
 * il bean {@code VisionTool} e' un singleton e non puo' tenere lo stato di un turno.
 */
class VisionCallCounter {

    static final String CONTEXT_KEY = "visionCallCounter";

    private final AtomicInteger calls = new AtomicInteger();

    /** Registra una chiamata e ritorna quante ce ne sono ora in questo turno (questa inclusa). */
    int next() {
        return calls.incrementAndGet();
    }
}
