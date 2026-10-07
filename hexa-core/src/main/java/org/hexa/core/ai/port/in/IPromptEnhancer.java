package org.hexa.core.ai.port.in;

import org.hexa.core.storage.domain.SourceImage;

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
     * Prompt per un inpainting (flux-fill-dev/pro): {@code draft} dice COSA deve comparire nella zona dipinta dall'utente (che il modello di
     * visione non vede); con {@code image} il modello la guarda solo per adattare il contenuto (luce, prospettiva, orientamento, stile),
     * senza riscrive solo la bozza col modello di testo. Il prompt descrive SOLO il contenuto della zona, non la scena intera.
     */
    String enhanceInpaint(String draft, SourceImage image);

    /**
     * Prompt per un'immagine generata PARTENDO da un'immagine (img2img, es. flux-dev-lora con upload): descrive il risultato finale, non
     * una modifica. {@code strength} e' la {@code prompt_strength} del form (0 = l'immagine resta quasi intatta, 1 = e' ignorata, null =
     * sconosciuta): da bassa il prompt dice solo cio' che cambia, da alta descrive la scena intera. Con {@code image} il modello di
     * visione la guarda, senza riscrive solo la bozza col modello di testo.
     */
    String enhanceImg2Img(String draft, SourceImage image, Double strength);

    /**
     * Prompt per un video (text-to-video o img2video). Con {@code image} il modello di visione la guarda e propone il movimento
     * coerente (anche con {@code draft} vuota); senza, riscrive solo la bozza col modello di testo. Se il modello di visione rifiuta
     * si riprova UNA volta col fallback.
     */
    String enhanceVideo(String draft, SourceImage image);
}
