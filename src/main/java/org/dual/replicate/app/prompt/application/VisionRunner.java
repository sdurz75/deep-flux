package org.dual.replicate.app.prompt.application;

import java.util.regex.Pattern;

import org.dual.replicate.app.prompt.domain.PromptEnhancementRefusedException;
import org.dual.replicate.app.prompt.port.out.IPromptModel;
import org.dual.replicate.app.prompt.port.out.ISourceImageScaler;
import org.dual.replicate.core.storage.domain.SourceImage;

/**
 * Una domanda a un modello di visione con il suo fallback e il riconoscimento dei rifiuti: la parte comune di "AI enhance"
 * ({@link PromptEnhancementService}) e della descrizione delle immagini ({@link ImageDescriptionService}).
 */
final class VisionRunner {

    /** Un rifiuto tipico ("I'm sorry, I can't...") o una risposta vuota. */
    private static final Pattern REFUSAL = Pattern.compile(
            "^\\s*(i['’]?m sorry|i am sorry|sorry|i can(['’]?t|not)|i['’]?m (unable|not able)|i am (unable|not able)|unable to|as an ai)",
            Pattern.CASE_INSENSITIVE);

    private final IPromptModel model;
    private final ISourceImageScaler scaler;
    private final String visionModel;
    private final String visionFallbackModel;

    VisionRunner(IPromptModel model, ISourceImageScaler scaler, String visionModel, String visionFallbackModel) {
        this.model = model;
        this.scaler = scaler;
        this.visionModel = visionModel;
        this.visionFallbackModel = visionFallbackModel;
    }

    /**
     * Chiede a {@code guide} di guardare {@code image} (ridotta se grande) con {@code text} come messaggio utente. Il fallback scatta sia per un
     * rifiuto sia per un ERRORE del modello principale (timeout, 402, modello non disponibile).
     *
     * @return il testo del modello, non vuoto e non un rifiuto
     * @throws PromptEnhancementRefusedException se entrambi i modelli rifiutano o rispondono vuoto
     */
    String ask(String operation, String guide, String text, SourceImage image) {
        SourceImage sized = scaler.fitForVision(image);
        boolean fallbackAvailable = !visionFallbackModel.isBlank() && !visionFallbackModel.equals(visionModel);

        String result = null;
        RuntimeException failure = null;
        try {
            result = model.complete(operation, guide, text, visionModel, sized);
        } catch (RuntimeException e) {
            failure = e;
        }
        if ((failure != null || isRefusal(result)) && fallbackAvailable) {
            try {
                result = model.complete(operation, guide, text, visionFallbackModel, sized);
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

    static boolean isRefusal(String result) {
        return result == null || result.isBlank() || REFUSAL.matcher(result).find();
    }
}
