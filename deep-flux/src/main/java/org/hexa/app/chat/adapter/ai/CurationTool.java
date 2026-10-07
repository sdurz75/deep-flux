package org.hexa.app.chat.adapter.ai;

import org.hexa.core.chat.port.in.IChatToolkit;
import org.hexa.core.chat.domain.ChatConversation;
import org.hexa.core.chat.port.in.IChatConversations;
import org.hexa.app.generation.domain.Generation;
import org.hexa.app.generation.domain.ReplicateException;
import org.hexa.app.generation.port.in.IGenerations;
import org.hexa.core.kernel.Tags;
import org.hexa.core.events.port.in.ISystemEvents;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Cura dell'archivio, solo su richiesta esplicita dell'utente: star, tag (di generazione, di file e della conversazione corrente) e titolo
 * della conversazione corrente. Sono mutazioni leggere e reversibili. Tutte IDEMPOTENTI (ricevono lo stato voluto, non un toggle): se
 * l'LLM le richiama o sbaglia lo stato di partenza, il risultato non si inverte a sorpresa. La conversazione che si puo' toccare e' SOLO
 * quella del turno ({@code ToolContext}): il modello non sceglie altre conversazioni. Nulla qui cancella o costa.
 */
@Component
@Order(40)
public class CurationTool implements IChatToolkit {

    private final IGenerations generations;
    private final IChatConversations conversations;
    private final ISystemEvents systemEvents;

    public CurationTool(IGenerations generations, IChatConversations conversations, ISystemEvents systemEvents) {
        this.generations = generations;
        this.conversations = conversations;
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
        return attempt("setFavourite", () -> {
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
        });
    }

    @Tool(description = "Add or remove a user tag on a generation (leave fileName empty) or on ONE of its files. Tags are short "
            + "labels the app lowercases; they make items findable later (searchArchive tag filter, the gallery). Use it ONLY when "
            + "the user explicitly asks to tag, label or untag something. Existing tags: listTags. Works on generated and imported images.")
    public String setTag(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact file name to tag, or empty to tag the whole generation", required = false) String filename,
            @ToolParam(description = "The tag: a short word or phrase, no commas") String tag,
            @ToolParam(description = "true to add the tag, false to remove it") boolean present) {
        String normalized = Tags.normalize(tag);
        if (generationId == null || normalized.isEmpty()) {
            return "Missing generation id or tag: ask the user or search first.";
        }
        String file = filename == null || filename.isBlank() ? null : filename.strip();
        return attempt("setTag", () -> {
            Generation generation = generations.find(generationId).orElse(null);
            if (generation == null) {
                return "No generation with id " + generationId + ".";
            }
            if (file != null && !generation.getImageFilenames().contains(file)) {
                return "Generation #" + generationId + " has no file named " + file + ".";
            }
            String target = (file == null ? "generation #" + generationId : file + " of generation #" + generationId);
            boolean has = file == null ? generation.getTags().contains(normalized) : generation.tagsOf(file).contains(normalized);
            if (has == present) {
                return "Nothing to change: " + target + (present ? " already has" : " does not have") + " the tag \"" + normalized + "\".";
            }
            if (present) {
                generations.addTag(generationId, file, normalized);
                return "Tagged " + target + " with \"" + normalized + "\".";
            }
            generations.removeTag(generationId, file, normalized);
            return "Removed the tag \"" + normalized + "\" from " + target + ".";
        });
    }

    @Tool(description = "Add or remove a user tag on THIS conversation (the one you are in; you cannot touch others). Use it ONLY "
            + "when the user explicitly asks to tag or label the conversation.")
    public String setConversationTag(
            @ToolParam(description = "The tag: a short word or phrase, no commas") String tag,
            @ToolParam(description = "true to add the tag, false to remove it") boolean present,
            ToolContext toolContext) {
        String normalized = Tags.normalize(tag);
        if (normalized.isEmpty()) {
            return "Missing tag: ask the user.";
        }
        return attempt("setConversationTag", () -> {
            Long id = currentConversation(toolContext);
            ChatConversation conversation = id == null ? null : conversations.find(id).orElse(null);
            if (conversation == null) {
                return "The current conversation is not available.";
            }
            if (conversation.getTags().contains(normalized) == present) {
                return "Nothing to change: this conversation " + (present ? "already has" : "does not have") + " the tag \"" + normalized + "\".";
            }
            if (present) {
                conversations.addTag(id, normalized);
                return "Tagged this conversation with \"" + normalized + "\".";
            }
            conversations.removeTag(id, normalized);
            return "Removed the tag \"" + normalized + "\" from this conversation.";
        });
    }

    @Tool(description = "Rename THIS conversation (the one you are in). Use it ONLY when the user asks to rename it or to give it a "
            + "title; propose the title first if they leave the choice to you. It replaces the title the app derived from the first message.")
    public String renameConversation(
            @ToolParam(description = "The new title: short, descriptive, in the user's language") String title,
            ToolContext toolContext) {
        if (title == null || title.isBlank()) {
            return "Missing title: ask the user.";
        }
        return attempt("renameConversation", () -> {
            Long id = currentConversation(toolContext);
            if (id == null || conversations.find(id).isEmpty()) {
                return "The current conversation is not available.";
            }
            return "Renamed this conversation to \"" + conversations.rename(id, title).getTitle() + "\".";
        });
    }

    private static Long currentConversation(ToolContext toolContext) {
        return toolContext != null && toolContext.getContext().get(LibraryTool.CONVERSATION_ID_CONTEXT_KEY) instanceof Long id ? id : null;
    }

    /** Un rifiuto atteso (REJECTED, es. troppi tag) torna al modello com'e'; un guasto vero e' registrato e il modello dice all'utente che non ha funzionato. */
    private String attempt(String operation, java.util.function.Supplier<String> action) {
        try {
            return action.get();
        } catch (ReplicateException | IllegalArgumentException e) {
            return "Not done: " + e.getMessage();
        } catch (RuntimeException e) {
            systemEvents.record(operation, e);
            return "Not done: internal error (" + ISystemEvents.sanitize(e) + "). Tell the user it did not work.";
        }
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.curation";
    }
}
