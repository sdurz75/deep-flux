package org.dual.replicate.app.chat.adapter.ai;

import org.dual.replicate.app.chat.domain.ChatAction;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Azioni distruttive o a pagamento PROPOSTE dalla chat: i tool qui NON mutano nulla (leggono soltanto, per rifiutare subito le
 * proposte senza senso) e depositano nel {@link ActionProposalHolder} del turno una {@link ChatAction}. La UI la rende come bottone
 * (htmlClassUtilities {@code chat-action} in deep-chat.html) e solo il click dell'utente, dopo conferma, chiama l'endpoint esistente
 * di {@code generation}: il consenso non e' mai dedotto dall'LLM. Rigenerare, animare e usare come sorgente non avviano nulla: aprono
 * la form precompilata, e la prediction parte solo se l'utente preme "Genera" li'.
 */
@Component
@Order(50)
public class ActionProposalTool implements ChatToolkit {

    private static final String PROPOSED = " Nothing has been done yet: the user will see a confirmation button under your reply "
            + "and decides there. Tell them so; never say the action was carried out.";

    private final IGenerations generations;
    private final ISystemEvents systemEvents;

    public ActionProposalTool(IGenerations generations, ISystemEvents systemEvents) {
        this.generations = generations;
        this.systemEvents = systemEvents;
    }

    @Tool(description = "Propose to stop a generation that is still running. This does NOT stop it: it shows the user a "
            + "button to confirm. Use it only when the user asks to stop or abort a generation in progress.")
    public String proposeCancel(@ToolParam(description = "The generation id, without the hash") Long generationId,
                                ToolContext toolContext) {
        return propose("proposeCancel", generationId, toolContext, generation -> generation.isTerminal()
                ? "Generation #" + generationId + " is already finished (" + generation.getStatus() + "): nothing to stop."
                : null, ignored -> ChatAction.cancel(generationId));
    }

    @Tool(description = "Propose to delete a generation and all its files permanently. This does NOT delete anything: it "
            + "shows the user a button to confirm. Use it only when the user asks to delete or remove a generation.")
    public String proposeDelete(@ToolParam(description = "The generation id, without the hash") Long generationId,
                                ToolContext toolContext) {
        return propose("proposeDelete", generationId, toolContext, generation -> null, ignored -> ChatAction.delete(generationId));
    }

    @Tool(description = "Propose to regenerate an image with the same prompt and the same seed. This does NOT generate "
            + "anything: it shows the user a button that opens the generation form pre-filled with the original model, LoRA, "
            + "parameters, prompt and seed; the user presses Generate there.")
    public String proposeRegenerateWithSeed(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact file name whose seed to reuse, from getGeneration or conversationGallery") String filename,
            ToolContext toolContext) {
        return propose("proposeRegenerateWithSeed", generationId, toolContext, generation -> {
            if (generation.isImported()) {
                return "Generation #" + generationId + " is an imported image, not a generation: it has no model, prompt or seed to reuse.";
            }
            if (generation.getKind() != GenerationKind.IMAGE) {
                return "Only image generations can be regenerated this way (not videos).";
            }
            // La form riapre modello, parametri, prompt e seed, ma non puo' rimettere un file caricato (sorgente o maschera): il
            // risultato sarebbe un'altra immagine, non la stessa col seed.
            if (generation.getSourceUploadFilename() != null || generation.getMaskUploadFilename() != null) {
                return "This generation started from an uploaded image or mask, which cannot be reopened from a link: it cannot be regenerated this way.";
            }
            // Una sorgente presa da un'altra generazione si rimette solo se quell'immagine esiste ancora.
            if (generation.getSourceGenerationId() != null
                    && generations.findAnimatableSource(generation.getSourceGenerationId(), generation.getSourceImageFilename()).isEmpty()) {
                return "The source image of this generation no longer exists: it cannot be regenerated this way.";
            }
            if (filename == null || !generation.getImageFilenames().contains(filename)) {
                return "Generation #" + generationId + " has no file named " + filename + ".";
            }
            if (generation.reusableSeedOf(filename) == null) {
                return "That file has no reproducible seed.";
            }
            return null;
        }, generation -> ChatAction.regenerate(generationId, filename, generation.getModel()));
    }

    @Tool(description = "Propose to animate one image into a video. This does NOT generate anything: it shows the user a button "
            + "that opens the video form with this image as the source; the user presses Generate there (the video is paid). Only for "
            + "succeeded image files. Use it when the user wants to animate or bring an image to life.")
    public String proposeAnimate(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact image file name, from getGeneration or conversationGallery") String filename,
            ToolContext toolContext) {
        return propose("proposeAnimate", generationId, toolContext, generation -> usableSourceRefusal(generationId, filename),
                generation -> ChatAction.animate(generationId, filename));
    }

    @Tool(description = "Propose to use one image as the starting point of a new image (image-to-image, edit with Kontext, "
            + "inpainting with a mask). This does NOT generate anything: it shows the user a button that opens the image form with "
            + "this image as the source, where they choose the model and press Generate. Only for succeeded image files, "
            + "generated or imported.")
    public String proposeUseAsSource(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact image file name, from getGeneration or conversationGallery") String filename,
            ToolContext toolContext) {
        return propose("proposeUseAsSource", generationId, toolContext, generation -> usableSourceRefusal(generationId, filename),
                generation -> ChatAction.useAsSource(generationId, filename));
    }

    @Tool(description = "Propose to delete ONE file of a generation permanently (deleting the last file deletes the whole "
            + "generation). This does NOT delete anything: it shows the user a button to confirm. Use it only when the user asks to "
            + "delete a specific image or video and not the whole generation.")
    public String proposeDeleteFile(
            @ToolParam(description = "The generation id, without the hash") Long generationId,
            @ToolParam(description = "The exact file name, from getGeneration or conversationGallery") String filename,
            ToolContext toolContext) {
        return propose("proposeDeleteFile", generationId, toolContext, generation ->
                        filename == null || !generation.getImageFilenames().contains(filename)
                                ? "Generation #" + generationId + " has no file named " + filename + "." : null,
                generation -> ChatAction.deleteFile(generationId, filename));
    }

    /** Sorgente valida = un'immagine di una generazione RIUSCITA (la stessa regola della form: mai un text-to-video silenzioso). */
    private String usableSourceRefusal(Long generationId, String filename) {
        if (filename == null || generations.findAnimatableSource(generationId, filename).isEmpty()) {
            return "Generation #" + generationId + " has no succeeded image file named " + filename + " (videos and failed or unfinished generations cannot be a source).";
        }
        return null;
    }

    private String propose(String operation, Long generationId, ToolContext toolContext,
                           java.util.function.Function<Generation, String> refusal,
                           java.util.function.Function<Generation, ChatAction> action) {
        if (generationId == null) {
            return "Missing generation id: ask the user or search for it first.";
        }
        try {
            Generation generation = generations.find(generationId).orElse(null);
            if (generation == null) {
                return "No generation with id " + generationId + ".";
            }
            String refused = refusal.apply(generation);
            if (refused != null) {
                return refused;
            }
            if (!(toolContext.getContext().get(ActionProposalHolder.CONTEXT_KEY) instanceof ActionProposalHolder holder)) {
                return "Proposals are not available in this context.";
            }
            if (!holder.add(action.apply(generation))) {
                return "That proposal was already made in this turn (or too many proposals): do not repeat it.";
            }
            return "Proposal prepared for generation #" + generationId + "." + PROPOSED;
        } catch (RuntimeException e) {
            systemEvents.record(operation, e);
            return "Could not prepare the proposal (" + ISystemEvents.sanitize(e) + "). Tell the user it did not work.";
        }
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.actions";
    }
}
