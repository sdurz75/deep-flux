package org.dual.hexa.ai.llm.application;

import org.dual.hexa.core.storage.domain.SourceImage;
import org.dual.hexa.ai.llm.domain.PromptEnhancementRefusedException;
import org.dual.hexa.ai.llm.port.in.IPromptEnhancer;
import org.dual.hexa.ai.llm.port.out.IPromptModel;
import org.dual.hexa.ai.llm.port.out.ISourceImageScaler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Use case dell'"AI enhance": riscrittura one-shot di una bozza di prompt (anche in italiano) in un prompt Flux ben formato in
 * inglese (fragments/app/generate-form.html :: promptField, GenerationController#enhancePrompt). Le guide, la scelta fra modello di
 * testo e di visione, il fallback e il riconoscimento dei rifiuti sono qui; la chiamata al modello e' dietro {@link IPromptModel}.
 */
@Service
@ConditionalOnProperty({"generateForm.prompt-enhancement-guide", "generateForm.video-prompt-enhancement-guide",
        "generateForm.edit-prompt-enhancement-guide", "generateForm.inpaint-prompt-enhancement-guide", "generateForm.img2img-prompt-enhancement-guide"})
public class PromptEnhancementService implements IPromptEnhancer {

    private final IPromptModel model;
    private final VisionRunner vision;
    private final String imageGuide;
    private final String videoGuide;
    private final String editGuide;
    private final String inpaintGuide;
    private final String img2imgGuide;

    public PromptEnhancementService(IPromptModel model,
                                     ISourceImageScaler scaler,
                                     @Value("${generateForm.prompt-enhancement-guide}") String promptEnhancementGuide,
                                     @Value("${generateForm.video-prompt-enhancement-guide}") String videoGuide,
                                     @Value("${generateForm.edit-prompt-enhancement-guide}") String editGuide,
                                     @Value("${generateForm.inpaint-prompt-enhancement-guide}") String inpaintGuide,
                                     @Value("${generateForm.img2img-prompt-enhancement-guide}") String img2imgGuide,
                                     @Value("${enhancer.vision-model}") String visionModel,
                                     @Value("${enhancer.vision-fallback-model}") String visionFallbackModel) {
        this.model = model;
        this.vision = new VisionRunner(model, scaler, visionModel, visionFallbackModel);
        this.imageGuide = promptEnhancementGuide;
        this.videoGuide = videoGuide;
        this.editGuide = editGuide;
        this.inpaintGuide = inpaintGuide;
        this.img2imgGuide = img2imgGuide;
    }

    @Override
    public String enhance(String draftPrompt) {
        return requireText(model.complete("enhance", imageGuide, draftPrompt, null, null));
    }

    /**
     * Prompt per un'istruzione di modifica (flux-kontext-dev): {@code draft} e' cio' che l'utente
     * vuole cambiare, con {@code image} il modello di visione la guarda per nominare gli elementi
     * reali; senza, riscrive solo la bozza col modello di testo. Rifiuti gestiti come in
     * {@link #enhanceVideo}.
     */
    @Override
    public String enhanceEdit(String draft, SourceImage image) {
        return rewriteWithVision(editGuide, draft, image);
    }

    /**
     * Prompt per un inpainting (flux-fill-dev/pro): descrive SOLO cio' che compare nella zona dipinta (le fonti concordano: una descrizione
     * dell'intera scena crea cuciture e segnali in conflitto con i bordi), con i pochi indizi di integrazione che l'immagine suggerisce
     * (luce, prospettiva, orientamento del volto, stile). Il modello di visione non vede la zona dipinta: la deduce dalla bozza.
     * Rifiuti gestiti come in {@link #enhanceVideo}.
     */
    @Override
    public String enhanceInpaint(String draft, SourceImage image) {
        return rewriteWithVision(inpaintGuide, draft, image);
    }

    /**
     * Prompt per un img2img: la forza e' accodata alla bozza come riga di contesto (la guida spiega come leggerla), cosi' resta
     * un solo percorso di visione/fallback/rifiuti come per le altre varianti. Rifiuti gestiti come in {@link #enhanceVideo}.
     */
    @Override
    public String enhanceImg2Img(String draft, SourceImage image, Double strength) {
        String text = strength == null ? draft : draft + "\n\n[prompt_strength: " + strength + "]";
        return rewriteWithVision(img2imgGuide, text, image);
    }

    /**
     * Prompt per un video (text-to-video o img2video). Con {@code image} il modello di
     * visione la guarda e propone il movimento coerente (anche con {@code draft} vuota);
     * senza, riscrive solo la bozza col modello di testo di default. Se il modello di
     * visione rifiuta si riprova UNA volta col fallback, poi
     * {@link PromptEnhancementRefusedException}.
     */
    @Override
    public String enhanceVideo(String draft, SourceImage image) {
        String text = (draft == null || draft.isBlank())
                ? "Propose an animation prompt for this image." : draft;
        return rewriteWithVision(videoGuide, text, image);
    }

    private String rewriteWithVision(String guide, String text, SourceImage image) {
        if (image == null) {
            return requireText(model.complete("enhance", guide, text, null, null));
        }
        return vision.ask("enhanceVision", guide, text, image);
    }

    /**
     * Una risposta vuota o un rifiuto ("I'm sorry, I can't...") NON devono sovrascrivere la bozza dell'utente: sono trattati come un
     * rifiuto (bozza conservata, errore mostrato). Vale anche per il percorso solo-testo: prima li' si controllava solo il vuoto e un
     * rifiuto finiva nella textarea come se fosse il prompt riscritto.
     */
    private static String requireText(String result) {
        if (isRefusal(result)) {
            throw new PromptEnhancementRefusedException(result == null ? "" : result.trim());
        }
        return result.trim();
    }

    static boolean isRefusal(String result) {
        return VisionRunner.isRefusal(result);
    }
}
