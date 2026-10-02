package org.dual.replicate.app.chat.adapter.ai;

import org.dual.replicate.app.chat.domain.ChatAction;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Azioni distruttive o a pagamento PROPOSTE dalla chat: i tool qui NON mutano nulla (leggono soltanto, per rifiutare subito le
 * proposte senza senso) e depositano nel {@link ActionProposalHolder} del turno una {@link ChatAction}. La UI la rende come bottone
 * (htmlClassUtilities {@code chat-action} in deep-chat.html) e solo il click dell'utente, dopo conferma, chiama l'endpoint esistente
 * di {@code generation}: il consenso non e' mai dedotto dall'LLM. Rigenerare non avvia nulla: apre la form con prompt e seed
 * nello slot globale, e la prediction parte solo se l'utente preme "Genera" li'.
 */
@Component
public class ActionProposalTool {

    private static final String PROPOSED = " Nothing has been done yet: the user will see a confirmation button under your reply "
            + "and decides there. Tell them so; never say the action was carried out.";

    private final IGenerations generations;
    private final IModelCatalog modelCatalog;
    private final ISystemEvents systemEvents;

    public ActionProposalTool(IGenerations generations, IModelCatalog modelCatalog, ISystemEvents systemEvents) {
        this.generations = generations;
        this.modelCatalog = modelCatalog;
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
            if (generation.getKind() != GenerationKind.IMAGE || modelCatalog.containsEdit(generation.getModel())) {
                return "Only plain image generations can be regenerated this way (not videos or edits).";
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
}
