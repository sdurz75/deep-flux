package org.dual.hexa.ai.llm.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dual.hexa.ai.llm.domain.ImageAnalysisException;
import org.dual.hexa.ai.llm.domain.ImageDescription;
import org.dual.hexa.ai.llm.domain.PromptEnhancementRefusedException;
import org.dual.hexa.ai.llm.port.in.IImageDescriber;
import org.dual.hexa.ai.llm.port.out.IPromptModel;
import org.dual.hexa.ai.llm.port.out.ISourceImageScaler;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Use case "descrivi l'immagine": una domanda al modello di visione ({@link VisionRunner}: stesso fallback e stessi rifiuti dell'"AI enhance")
 * con la guida {@code imageAnalysis.guide}, e la lettura del formato {@code DESCRIPTION: ... TAGS: a, b} (testo semplice: funziona anche con
 * modelli che non rispettano un JSON).
 */
@Service
@ConditionalOnProperty("imageAnalysis.guide")
public class ImageDescriptionService implements IImageDescriber {

    /** Tetto della descrizione e dei tag: l'indice tronca comunque a {@code DocumentTypes.MAX_CHARS}. */
    static final int MAX_DESCRIPTION = 1200;
    static final int MAX_TAGS = 20;

    private static final Pattern FORMAT = Pattern.compile("(?is)DESCRIPTION\\s*:\\s*(.+?)\\s*TAGS\\s*:\\s*(.*)$");
    private static final Pattern DESCRIPTION_ONLY = Pattern.compile("(?is)DESCRIPTION\\s*:\\s*(.+)$");

    private static final String USER_MESSAGE = "Describe this image.";

    private final VisionRunner vision;
    private final String guide;

    public ImageDescriptionService(IPromptModel model, ISourceImageScaler scaler,
                                   @Value("${imageAnalysis.guide}") String guide,
                                   @Value("${enhancer.vision-model}") String visionModel,
                                   @Value("${enhancer.vision-fallback-model}") String visionFallbackModel) {
        this.vision = new VisionRunner(model, scaler, visionModel, visionFallbackModel);
        this.guide = guide;
    }

    @Override
    public ImageDescription describe(SourceImage image) {
        String raw;
        try {
            raw = vision.ask("describeImage", guide, USER_MESSAGE, image);
        } catch (PromptEnhancementRefusedException e) {
            throw new ImageAnalysisException(e.getMessage(), e);
        }
        return parse(raw);
    }

    static ImageDescription parse(String raw) {
        Matcher both = FORMAT.matcher(raw);
        String description;
        String tags;
        if (both.find()) {
            description = both.group(1);
            tags = both.group(2);
        } else {
            Matcher only = DESCRIPTION_ONLY.matcher(raw);
            if (!only.find()) {
                throw new ImageAnalysisException("Risposta del modello di visione non nel formato atteso: " + abbreviate(raw), null);
            }
            description = only.group(1);
            tags = "";
        }
        description = description.strip();
        if (description.isEmpty()) {
            throw new ImageAnalysisException("Descrizione vuota nella risposta del modello di visione", null);
        }
        if (description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION).strip();
        }
        return new ImageDescription(description, parseTags(tags));
    }

    private static List<String> parseTags(String tags) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String tag : tags.split("[,\\n;]")) {
            String clean = tag.strip().replaceAll("^[-*#\\s]+", "").strip();
            if (!clean.isEmpty()) {
                unique.add(clean);
            }
        }
        return unique.stream().limit(MAX_TAGS).toList();
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }
}
