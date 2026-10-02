package org.dual.replicate.app.prompt.application;

import java.util.regex.Pattern;

import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.app.prompt.domain.PromptEnhancementRefusedException;
import org.dual.replicate.app.prompt.port.in.IPromptEnhancer;
import org.dual.replicate.app.prompt.port.out.IPromptModel;
import org.dual.replicate.app.prompt.port.out.ISourceImageScaler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Use case dell'"AI enhance": riscrittura one-shot di una bozza di prompt (anche in italiano) in un prompt Flux ben formato in
 * inglese (fragments/app/generate-form.html :: promptField, GenerationController#enhancePrompt). Le guide, la scelta fra modello di
 * testo e di visione, il fallback e il riconoscimento dei rifiuti sono qui; la chiamata al modello e' dietro {@link IPromptModel}.
 */
@Service
public class PromptEnhancementService implements IPromptEnhancer {

    /** Un rifiuto tipico ("I'm sorry, I can't...") o una risposta vuota. */
    private static final Pattern REFUSAL = Pattern.compile(
            "^\\s*(i['\u2019]?m sorry|i am sorry|sorry|i can(['\u2019]?t|not)|i['\u2019]?m (unable|not able)|i am (unable|not able)|unable to|as an ai)",
            Pattern.CASE_INSENSITIVE);

    private final IPromptModel model;
    private final ISourceImageScaler scaler;
    private final String imageGuide;
    private final String videoGuide;
    private final String editGuide;
    private final String visionModel;
    private final String visionFallbackModel;

    public PromptEnhancementService(IPromptModel model,
                                     ISourceImageScaler scaler,
                                     @Value("${generateForm.prompt-enhancement-guide}") String promptEnhancementGuide,
                                     @Value("${generateForm.video-prompt-enhancement-guide}") String videoGuide,
                                     @Value("${generateForm.edit-prompt-enhancement-guide}") String editGuide,
                                     @Value("${enhancer.vision-model}") String visionModel,
                                     @Value("${enhancer.vision-fallback-model}") String visionFallbackModel) {
        this.model = model;
        this.scaler = scaler;
        this.imageGuide = promptEnhancementGuide;
        this.videoGuide = videoGuide;
        this.editGuide = editGuide;
        this.visionModel = visionModel;
        this.visionFallbackModel = visionFallbackModel;
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
        SourceImage sized = scaler.fitForVision(image);
        boolean fallbackAvailable = !visionFallbackModel.isBlank() && !visionFallbackModel.equals(visionModel);

        // Il fallback scatta sia per un rifiuto sia per un ERRORE del modello principale (timeout, 402, modello
        // non disponibile): prima un errore lo faceva abortire senza nemmeno provare il secondo modello.
        String result = null;
        RuntimeException failure = null;
        try {
            result = askVision(guide, visionModel, text, sized);
        } catch (RuntimeException e) {
            failure = e;
        }
        if ((failure != null || isRefusal(result)) && fallbackAvailable) {
            try {
                result = askVision(guide, visionFallbackModel, text, sized);
                failure = null;
            } catch (RuntimeException e) {
                if (failure != null) {
                    e.addSuppressed(failure);
                }
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (isRefusal(result)) {
            throw new PromptEnhancementRefusedException(result == null ? "" : result.trim());
        }
        return result.trim();
    }

    private String askVision(String guide, String visionModel, String text, SourceImage image) {
        return model.complete("enhanceVision", guide, text, visionModel, image);
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
        return result == null || result.isBlank() || REFUSAL.matcher(result).find();
    }
}
