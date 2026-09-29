package org.dual.replicate.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

/**
 * Riscrittura one-shot di una bozza di prompt (anche in italiano) in un
 * prompt Flux ben formato in inglese, per l'icona "AI enhance" accanto
 * alla textarea di /generations/new (fragments/generate-form.html ::
 * promptField, GenerationController#enhancePrompt). A differenza di
 * DeepChatService non c'e' conversazione ne' tool: {@link ChatClient.Builder}
 * e' prototype-scoped (verificato in ChatClientAutoConfiguration di
 * spring-ai-autoconfigure-model-chat-client), quindi questa istanza,
 * costruita senza defaultTools(...), non puo' in alcun modo invocare
 * ImageGenerationTool - nessun rischio di avviare una generazione Replicate
 * (spesa reale) da una semplice richiesta di riscrittura testo.
 */
@Service
public class PromptEnhancementService {

    /** Immagine sorgente di un img2video, come arriva al modello di visione. */
    public record SourceImage(byte[] bytes, String mimeType) {
    }

    /** Un rifiuto tipico ("I'm sorry, I can't...") o una risposta vuota. */
    private static final Pattern REFUSAL = Pattern.compile(
            "^\\s*(i['\u2019]?m sorry|i am sorry|sorry|i can(['\u2019]?t|not)|i['\u2019]?m (unable|not able)|i am (unable|not able)|unable to|as an ai)",
            Pattern.CASE_INSENSITIVE);

    /** Oltre questa dimensione l'immagine viene ridotta prima dell'invio (token del modello di visione). */
    private static final int DOWNSCALE_ABOVE_BYTES = 1_500_000;
    private static final int MAX_SIDE = 1024;

    private final ChatClient chatClient;
    private final String videoGuide;
    private final String visionModel;
    private final String visionFallbackModel;

    public PromptEnhancementService(ChatClient.Builder chatClientBuilder,
                                     @Value("${generateForm.prompt-enhancement-guide}") String promptEnhancementGuide,
                                     @Value("${generateForm.video-prompt-enhancement-guide}") String videoGuide,
                                     @Value("${enhancer.vision-model}") String visionModel,
                                     @Value("${enhancer.vision-fallback-model}") String visionFallbackModel) {
        this.chatClient = chatClientBuilder.defaultSystem(promptEnhancementGuide).build();
        this.videoGuide = videoGuide;
        this.visionModel = visionModel;
        this.visionFallbackModel = visionFallbackModel;
    }

    public String enhance(String draftPrompt) {
        String result = chatClient.prompt().user(draftPrompt).call().content();
        return result == null ? "" : result.trim();
    }

    /**
     * Prompt per un video (text-to-video o img2video). Con {@code image} il modello di
     * visione la guarda e propone il movimento coerente (anche con {@code draft} vuota);
     * senza, riscrive solo la bozza col modello di testo di default. Se il modello di
     * visione rifiuta si riprova UNA volta col fallback, poi
     * {@link PromptEnhancementRefusedException}.
     */
    public String enhanceVideo(String draft, SourceImage image) {
        String text = (draft == null || draft.isBlank())
                ? "Propose an animation prompt for this image." : draft;
        if (image == null) {
            String result = chatClient.prompt().system(videoGuide).user(text).call().content();
            return result == null ? "" : result.trim();
        }
        SourceImage sized = downscale(image);
        String result = askVision(visionModel, text, sized);
        if (isRefusal(result) && !visionFallbackModel.isBlank() && !visionFallbackModel.equals(visionModel)) {
            result = askVision(visionFallbackModel, text, sized);
        }
        if (isRefusal(result)) {
            throw new PromptEnhancementRefusedException(result == null ? "" : result.trim());
        }
        return result.trim();
    }

    private String askVision(String model, String text, SourceImage image) {
        return chatClient.prompt()
                .system(videoGuide)
                .options(OpenAiChatOptions.builder().model(model))
                .user(u -> u.text(text).media(MimeType.valueOf(image.mimeType()), new ByteArrayResource(image.bytes())))
                .call().content();
    }

    static boolean isRefusal(String result) {
        return result == null || result.isBlank() || REFUSAL.matcher(result).find();
    }

    /** Riduce a MAX_SIDE le immagini grandi (png/jpeg); webp o illeggibili passano invariate. */
    private static SourceImage downscale(SourceImage image) {
        if (image.bytes().length <= DOWNSCALE_ABOVE_BYTES) {
            return image;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.bytes()));
            if (source == null || Math.max(source.getWidth(), source.getHeight()) <= MAX_SIDE) {
                return image;
            }
            double scale = (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight());
            BufferedImage scaled = new BufferedImage((int) (source.getWidth() * scale), (int) (source.getHeight() * scale),
                    BufferedImage.TYPE_INT_RGB);
            var g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "jpg", out);
            return new SourceImage(out.toByteArray(), "image/jpeg");
        } catch (IOException e) {
            return image;
        }
    }
}
