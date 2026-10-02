package org.dual.replicate.app.chat.adapter.ai;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Mutazione leggera e reversibile: mette o toglie la star a un file. A differenza di {@link IGenerations#toggleFavourite} il tool e'
 * IDEMPOTENTE (riceve lo stato voluto): se l'LLM lo richiama o sbaglia stato di partenza, il risultato non si inverte a sorpresa.
 */
@Component
public class FavouriteTool {

    private final IGenerations generations;
    private final ISystemEvents systemEvents;

    public FavouriteTool(IGenerations generations, ISystemEvents systemEvents) {
        this.generations = generations;
        this.systemEvents = systemEvents;
    }

    @Tool(description = "Add or remove the favourite star of one file (image or video) of a generation. Use it ONLY when the "
            + "user explicitly asks to favourite or unfavourite something. Get the file name with getGeneration or "
            + "conversationGallery first; never guess it.")
    public String setFavourite(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact file name, as listed by conversationGallery") String filename,
            @ToolParam(description = "true to add the star, false to remove it") boolean favourite) {
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
            if (generation.getFavouriteFilenames().contains(filename) != favourite) {
                generations.toggleFavourite(generationId, filename);
            }
            return (favourite ? "Starred " : "Removed the star from ") + filename + " of generation #" + generationId + ".";
        } catch (ReplicateException e) {
            return "Not done: " + e.getMessage();
        } catch (RuntimeException e) {
            systemEvents.record("setFavourite", e);
            return "Not done: internal error (" + ISystemEvents.sanitize(e) + "). Tell the user it did not work.";
        }
    }
}
