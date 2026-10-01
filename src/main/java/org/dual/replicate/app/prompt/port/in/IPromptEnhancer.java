package org.dual.replicate.app.prompt.port.in;

import org.dual.replicate.core.storage.domain.SourceImage;

/**
 * "AI enhance": riscrittura one-shot di una bozza di prompt (anche in italiano) in un prompt ben formato. Un'azione puntuale, senza
 * conversazione ne' tool: non puo' in alcun modo avviare una generazione a pagamento. Un rifiuto o una risposta vuota del modello
 * e' {@code PromptEnhancementRefusedException} (la bozza dell'utente non va sovrascritta).
 */
public interface IPromptEnhancer {

    /** Prompt per un'immagine (guida generica Flux). */
    String enhance(String draftPrompt);

    /**
     * Prompt per un'istruzione di modifica (flux-kontext-dev): {@code draft} e' cio' che l'utente vuole cambiare; con {@code image} il
     * modello di visione la guarda per nominare gli elementi reali, senza riscrive solo la bozza col modello di testo.
     */
    String enhanceEdit(String draft, SourceImage image);

    /**
     * Prompt per un video (text-to-video o img2video). Con {@code image} il modello di visione la guarda e propone il movimento
     * coerente (anche con {@code draft} vuota); senza, riscrive solo la bozza col modello di testo. Se il modello di visione rifiuta
     * si riprova UNA volta col fallback.
     */
    String enhanceVideo(String draft, SourceImage image);
}
