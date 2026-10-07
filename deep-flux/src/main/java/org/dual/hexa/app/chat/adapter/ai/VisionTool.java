package org.dual.hexa.app.chat.adapter.ai;

import org.dual.hexa.ai.chat.port.in.IChatToolkit;
import java.util.Map;
import java.util.List;

import org.dual.hexa.app.generation.domain.AnalysisStatus;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.ai.llm.domain.ImageAnalysisException;
import org.dual.hexa.ai.llm.domain.ImageDescription;
import org.dual.hexa.ai.llm.port.in.IImageDescriber;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Lascia guardare al modello UNA immagine dell'archivio, quando prompt e tag non bastano ("cosa c'e' in questa foto?"). Un'importata gia'
 * analizzata risponde dall'analisi salvata, senza chiamate; per le altre il file va al modello di visione dell'app ({@link IImageDescriber},
 * lo stesso dell'analisi delle importate e di "AI enhance"), che costa token OpenRouter: per questo c'e' un tetto per turno
 * ({@code app.chat.max-vision-calls-per-turn}). Il risultato non si salva (nessuna modifica all'archivio) e i video non si guardano.
 */
@Component
@Order(80)
public class VisionTool implements IChatToolkit {

    private final IGenerations generations;
    private final IImageStorageService storage;
    private final IImageDescriber describer;
    private final ISystemEvents systemEvents;
    private final int maxCallsPerTurn;

    public VisionTool(IGenerations generations,
                      IImageStorageService storage,
                      IImageDescriber describer,
                      ISystemEvents systemEvents,
                      @Value("${app.chat.max-vision-calls-per-turn:2}") int maxCallsPerTurn) {
        this.generations = generations;
        this.storage = storage;
        this.describer = describer;
        this.systemEvents = systemEvents;
        this.maxCallsPerTurn = maxCallsPerTurn;
    }

    @Tool(description = "Look at ONE image of the archive and describe what it shows (subjects, style, setting, text, composition). "
            + "Use it when the user asks what is in an image, or wants something based on how it actually looks, and the prompt and "
            + "tags from getGeneration are not enough. An imported image already analysed is answered from the stored analysis; any "
            + "other image is sent to a vision model (limited calls per turn, costs tokens). Images only, not videos. "
            + "The image does not change and nothing is saved.")
    public String describeImage(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact file name, from getGeneration or conversationGallery") String filename,
            ToolContext toolContext) {
        if (generationId == null || filename == null) {
            return "Missing generation id or file name: get them with getGeneration or conversationGallery.";
        }
        try {
            Generation generation = generations.find(generationId).orElse(null);
            if (generation == null) {
                return "No generation with id " + generationId + ".";
            }
            if (!generation.getImageFilenames().contains(filename)) {
                return "Generation #" + generationId + " has no file named " + filename + ".";
            }
            if (generation.isVideo()) {
                return "That file is a video: only images can be looked at. Use the prompt from getGeneration instead.";
            }
            if (generation.isImported() && generation.getAnalysisStatus() == AnalysisStatus.DONE) {
                List<String> keywords = generation.getAnalysisTagList();
                return "Stored analysis of imported image #" + generationId + " (no model call):\nDescription: " + generation.getPrompt()
                        + (keywords.isEmpty() ? "" : "\nKeywords: " + String.join(", ", keywords));
            }
            if (!(toolContext.getContext().get(VisionCallCounter.CONTEXT_KEY) instanceof VisionCallCounter counter)) {
                return "Looking at images is not available in this context.";
            }
            if (counter.next() > maxCallsPerTurn) {
                return "Not done: the limit of " + maxCallsPerTurn + " image(s) looked at per reply was reached. "
                        + "Answer with what you have and offer to look at another image in the next message.";
            }
            ImageDescription description = describer.describe(storage.read(filename));
            return "What the vision model sees in file " + filename + " of generation #" + generationId + ":\nDescription: "
                    + description.description()
                    + (description.tags().isEmpty() ? "" : "\nKeywords: " + String.join(", ", description.tags()));
        } catch (ImageAnalysisException e) {
            // Rifiuto o risposta illeggibile: un esito atteso, non un guasto.
            return "The vision model declined or returned an unreadable answer for this image. Do not retry; "
                    + "tell the user and work from the prompt and tags instead.";
        } catch (RuntimeException e) {
            if (!(e instanceof RemoteServiceException remote) || remote.isReportable()) {
                systemEvents.record("describeImage", e);
            }
            return "Could not look at the image (" + ISystemEvents.sanitize(e) + "). Do not retry automatically; tell the user it did not work.";
        }
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.vision";
    }

    @Override
    public void beginTurn(Map<String, Object> toolContext) {
        toolContext.put(VisionCallCounter.CONTEXT_KEY, new VisionCallCounter());
    }
}
