package org.hexa.core.ai.application;

import java.util.Locale;
import java.util.regex.Pattern;

import org.hexa.core.ai.domain.CaptionStyle;
import org.hexa.core.ai.domain.ImageCaptionException;
import org.hexa.core.ai.domain.PromptEnhancementRefusedException;
import org.hexa.core.ai.port.in.IImageCaptioner;
import org.hexa.core.ai.port.out.IPromptModel;
import org.hexa.core.ai.port.out.ISourceImageScaler;
import org.hexa.core.storage.domain.SourceImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Use case "didascalizza l'immagine": una domanda al modello di visione ({@link VisionRunner}: stesso fallback e stessi rifiuti dell'"AI enhance") con la
 * guida del tipo di LoRA ({@code trainingCaption.subject-guide|style-guide}), e una ripulitura del testo (il modello aggiunge etichette, virgolette, a capo).
 * La trigger word e' una garanzia dello use case, non una speranza sul modello.
 */
@Service
@ConditionalOnProperty({"trainingCaption.subject-guide", "trainingCaption.style-guide"})
public class ImageCaptionService implements IImageCaptioner {

    /** Tetto della didascalia: il trainer ne usa una per immagine e un testo molto lungo viene comunque tagliato dal suo tokenizer. */
    static final int MAX_CAPTION = 600;

    private static final Pattern LABEL = Pattern.compile("(?i)^(caption|didascalia)\\s*:\\s*");
    private static final Pattern WRAPPING_QUOTES = Pattern.compile("^[\"“”`]+|[\"“”`]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final VisionRunner vision;
    private final String subjectGuide;
    private final String styleGuide;

    public ImageCaptionService(IPromptModel model, ISourceImageScaler scaler,
                               @Value("${trainingCaption.subject-guide}") String subjectGuide,
                               @Value("${trainingCaption.style-guide}") String styleGuide,
                               @Value("${enhancer.vision-model}") String visionModel,
                               @Value("${enhancer.vision-fallback-model}") String visionFallbackModel) {
        this.vision = new VisionRunner(model, scaler, visionModel, visionFallbackModel);
        this.subjectGuide = subjectGuide;
        this.styleGuide = styleGuide;
    }

    @Override
    public String caption(SourceImage image, String triggerWord, CaptionStyle style) {
        String guide = style == CaptionStyle.STYLE ? styleGuide : subjectGuide;
        String raw;
        try {
            raw = vision.ask("captionTrainingImage", guide, "Trigger word: " + triggerWord + "\nWrite the caption for this image.", image);
        } catch (PromptEnhancementRefusedException e) {
            throw new ImageCaptionException(e.getMessage(), e);
        }
        return clean(raw, triggerWord);
    }

    static String clean(String raw, String triggerWord) {
        String text = WHITESPACE.matcher(raw).replaceAll(" ").strip();
        text = LABEL.matcher(text).replaceFirst("");
        text = WRAPPING_QUOTES.matcher(text).replaceAll("").strip();
        if (text.isEmpty()) {
            throw new ImageCaptionException("Didascalia vuota nella risposta del modello di visione", null);
        }
        if (!text.toLowerCase(Locale.ROOT).contains(triggerWord.toLowerCase(Locale.ROOT))) {
            text = triggerWord + ", " + text;
        }
        return text.length() <= MAX_CAPTION ? text : truncated(text);
    }

    /** Taglia a una parola intera (la trigger word, in testa, resta sempre). */
    private static String truncated(String text) {
        int cut = text.lastIndexOf(' ', MAX_CAPTION);
        return text.substring(0, cut > 0 ? cut : MAX_CAPTION).strip();
    }
}
