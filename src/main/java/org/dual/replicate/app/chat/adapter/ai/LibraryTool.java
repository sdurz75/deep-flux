package org.dual.replicate.app.chat.adapter.ai;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GalleryItem;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.core.events.domain.SystemEvent;
import org.dual.replicate.core.events.domain.SystemEventSeverity;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool di SOLA LETTURA del modello di /deep-chat sul resto dell'app: catalogo modelli, LoRA anagrafati, dettaglio di una generazione,
 * contenuto della conversazione corrente, eventi di sistema recenti. Nessun tool qui modifica qualcosa. Niente segreti nell'output:
 * dei LoRA non esce la sorgente (potrebbe essere un URL privato), delle generazioni non i parametri grezzi (portano gli id dei token),
 * degli eventi solo il messaggio gia' sanitizzato. Come gli altri tool, un guasto e' registrato e il modello riceve un testo d'errore.
 */
@Component
public class LibraryTool {

    /** Chiave ToolContext con l'id della conversazione corrente (messo da SpringAiAssistant). */
    public static final String CONVERSATION_ID_CONTEXT_KEY = "conversationId";

    private static final int MAX_ITEMS = 20;
    private static final int MAX_EVENTS = 10;
    private static final int PROMPT_SNIPPET = 200;

    private final IModelCatalog modelCatalog;
    private final ILoraPresets loraPresets;
    private final IGenerations generations;
    private final ISystemEvents systemEvents;

    public LibraryTool(IModelCatalog modelCatalog, ILoraPresets loraPresets, IGenerations generations, ISystemEvents systemEvents) {
        this.modelCatalog = modelCatalog;
        this.loraPresets = loraPresets;
        this.generations = generations;
        this.systemEvents = systemEvents;
    }

    @Tool(description = "List the image models the user can select in the UI, with a short description. Use it when the user "
            + "asks which models exist or you want to suggest a different one (you cannot switch model yourself: the user "
            + "changes the selection in the UI).")
    public String listModels(ToolContext toolContext) {
        try {
            Object selected = toolContext.getContext().get(ImageGenerationTool.MODEL_CONTEXT_KEY);
            List<ReplicateModel> models = modelCatalog.models(GenerationKind.IMAGE);
            if (models.isEmpty()) {
                return "No image model is configured.";
            }
            return models.stream().map(model -> "- %s%s%s".formatted(model.getIdentifier(),
                            model.getIdentifier().equals(selected) ? " (currently selected)" : "",
                            blank(model.getDescription()) ? "" : ": " + oneLine(model.getDescription(), PROMPT_SNIPPET)))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return failure("listModels", e);
        }
    }

    @Tool(description = "List the user's saved LoRAs with their trigger words and default scale. When the user names a LoRA "
            + "or style, use its trigger words in the image prompt.")
    public String listLoraPresets() {
        try {
            List<ILoraPresets.LoraView> presets = loraPresets.list();
            if (presets.isEmpty()) {
                return "No LoRA is saved.";
            }
            return presets.stream().map(lora -> "- %s (scale %s)%s%s".formatted(lora.name(), lora.scale(),
                            blank(lora.triggerWords()) ? "" : ", trigger words: " + oneLine(lora.triggerWords(), PROMPT_SNIPPET),
                            blank(lora.note()) ? "" : ", note: " + oneLine(lora.note(), PROMPT_SNIPPET)))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return failure("listLoraPresets", e);
        }
    }

    @Tool(description = "Get the details of one generation by id: kind, status, model, prompt, estimated cost, the exact file "
            + "names (with favourite flag and reproducible seed of each) and, if it failed, the error. Generations can be opened at /generations/{id}.")
    public String getGeneration(@ToolParam(description = "The generation id, without the hash") Long id) {
        if (id == null) {
            return "Missing generation id.";
        }
        try {
            return generations.find(id).map(LibraryTool::describe).orElse("No generation with id " + id + ".");
        } catch (RuntimeException e) {
            return failure("getGeneration", e);
        }
    }

    @Tool(description = "List what was generated in THIS conversation (most recent last, at most " + MAX_ITEMS + "): generation id, "
            + "file name and prompt. Use it for questions like \"what have we made so far?\".")
    public String conversationGallery(ToolContext toolContext) {
        try {
            if (!(toolContext.getContext().get(CONVERSATION_ID_CONTEXT_KEY) instanceof Long conversationId)) {
                return "The current conversation is unknown.";
            }
            List<GalleryItem> items = generations.succeededItemsForConversation(conversationId);
            if (items.isEmpty()) {
                return "Nothing has been generated in this conversation yet.";
            }
            List<GalleryItem> shown = items.size() > MAX_ITEMS ? items.subList(items.size() - MAX_ITEMS, items.size()) : items;
            String lines = shown.stream().map(item -> "- #%d %s%s: %s".formatted(item.generation().getId(), item.filename(),
                            item.generation().getFavouriteFilenames().contains(item.filename()) ? " (favourite)" : "",
                            oneLine(item.generation().getPrompt(), PROMPT_SNIPPET)))
                    .collect(Collectors.joining("\n"));
            return items.size() > shown.size() ? "(showing the last %d of %d files)\n%s".formatted(shown.size(), items.size(), lines) : lines;
        } catch (RuntimeException e) {
            return failure("conversationGallery", e);
        }
    }

    @Tool(description = "List the most recent system errors and warnings of the app (failed generations, unreachable services, "
            + "expiring tokens). Use it to explain why something failed.")
    public String recentEvents(
            @ToolParam(description = "Optional minimum severity: error or warning (default: both)", required = false) String severity) {
        try {
            SystemEventSeverity wanted = null;
            if (!blank(severity)) {
                try {
                    wanted = SystemEventSeverity.valueOf(severity.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "Invalid severity (" + severity + "): use error or warning, or omit it.";
                }
            }
            List<SystemEvent> events = systemEvents.list(wanted, 0, MAX_EVENTS).content();
            if (events.isEmpty()) {
                return "No recent events.";
            }
            return events.stream().map(event -> "- [%s] %s/%s%s: %s (x%d, last %s)".formatted(event.getSeverity(), event.getSource(),
                            event.getOperation(), blank(event.getSubject()) ? "" : " " + event.getSubject(),
                            ISystemEvents.sanitizeText(event.getMessage()), event.getOccurrences(), event.getLastSeenAt()))
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException e) {
            return failure("recentEvents", e);
        }
    }

    private static String describe(Generation g) {
        // Un'immagine importata non ha un modello ne' un prompt: ha una descrizione prodotta dall'analisi (vuota finche' non c'e').
        StringBuilder out = new StringBuilder(g.isImported()
                ? "Generation #%d: imported image (not generated, no model), status %s".formatted(g.getId(), g.getStatus())
                : "Generation #%d: %s, status %s, model %s".formatted(g.getId(), g.getKind(), g.getStatus(), g.getModel()));
        out.append(g.isImported() ? "\nDescription: " : "\nPrompt: ").append(oneLine(g.getPrompt(), 600));
        out.append("\nFiles: %d of %d requested".formatted(g.getImageFilenames().size(), g.getRequestedOutputs()));
        for (String filename : g.getImageFilenames()) {
            Long seed = g.reusableSeedOf(filename);
            out.append("\n- ").append(filename);
            if (g.getFavouriteFilenames().contains(filename)) {
                out.append(" (favourite)");
            }
            out.append(seed != null ? ", seed " + seed : ", no reproducible seed");
        }
        if (g.getSeed() != null) {
            out.append("\nBatch seed: ").append(g.getSeed());
        }
        if (g.getCostUsd() != null) {
            out.append("\nEstimated cost: $").append(g.getCostUsd().toPlainString());
        }
        if (!blank(g.getErrorMessage())) {
            out.append("\nError: ").append(oneLine(g.getErrorMessage(), PROMPT_SNIPPET));
        }
        return out.toString();
    }

    private String failure(String operation, RuntimeException e) {
        systemEvents.record(operation, e);
        return "This lookup is not available right now (" + ISystemEvents.sanitize(e)
                + "). Answer without it, telling the user it did not work.";
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String oneLine(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }
}
