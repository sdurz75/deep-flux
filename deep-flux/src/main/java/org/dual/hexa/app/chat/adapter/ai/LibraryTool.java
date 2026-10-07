package org.dual.hexa.app.chat.adapter.ai;

import org.dual.hexa.ai.chat.port.in.IChatToolkit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.ReplicateModel;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.app.generation.port.in.IModelCatalog;
import org.dual.hexa.core.events.domain.SystemEvent;
import org.dual.hexa.core.events.domain.SystemEventSeverity;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tool di SOLA LETTURA del modello di /deep-chat sul resto dell'app: catalogo modelli, LoRA anagrafati, tag in uso, dettaglio di una
 * generazione o immagine importata, contenuto della conversazione corrente, eventi di sistema recenti. Nessun tool qui modifica qualcosa. Niente segreti nell'output:
 * dei LoRA non esce la sorgente (potrebbe essere un URL privato), delle generazioni non i parametri grezzi (portano gli id dei token: solo un riassunto a whitelist),
 * degli eventi solo il messaggio gia' sanitizzato. Come gli altri tool, un guasto e' registrato e il modello riceve un testo d'errore.
 */
@Component
@Order(20)
public class LibraryTool implements IChatToolkit {

    /** Chiave ToolContext con l'id della conversazione corrente (messo da SpringAiAssistant). */
    public static final String CONVERSATION_ID_CONTEXT_KEY = org.dual.hexa.ai.chat.domain.ChatTurnContext.CONVERSATION_ID;

    private static final int MAX_ITEMS = 20;
    private static final int MAX_EVENTS = 10;
    private static final int PROMPT_SNIPPET = 200;

    /** I soli parametri che il modello vede (nessun LoRA, token o sorgente: il JSON grezzo porta gli id dei token). */
    private static final List<String> VISIBLE_PARAMETERS = List.of("aspect_ratio", "width", "height", "num_outputs", "megapixels",
            "prompt_strength", "guidance", "guidance_scale", "num_inference_steps", "steps");

    private final IModelCatalog modelCatalog;
    private final ILoraPresets loraPresets;
    private final IGenerations generations;
    private final ISystemEvents systemEvents;
    private final ObjectMapper objectMapper;

    public LibraryTool(IModelCatalog modelCatalog, ILoraPresets loraPresets, IGenerations generations, ISystemEvents systemEvents,
                       ObjectMapper objectMapper) {
        this.modelCatalog = modelCatalog;
        this.loraPresets = loraPresets;
        this.generations = generations;
        this.systemEvents = systemEvents;
        this.objectMapper = objectMapper;
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
            String list = models.stream().map(model -> "- %s%s%s".formatted(model.getIdentifier(),
                            model.getIdentifier().equals(selected) ? " (currently selected)" : "",
                            blank(model.getDescription()) ? "" : ": " + oneLine(model.getDescription(), PROMPT_SNIPPET)))
                    .collect(Collectors.joining("\n"));
            // Modelli che partono da un'immagine (edit, inpainting): non si generano da qui, ma esistono e l'utente deve saperlo.
            Set<String> generatable = models.stream().map(ReplicateModel::getIdentifier).collect(Collectors.toSet());
            String needSource = modelCatalog.formModels(GenerationKind.IMAGE).stream().map(ReplicateModel::getIdentifier)
                    .filter(id -> !generatable.contains(id)).collect(Collectors.joining(", "));
            return needSource.isEmpty() ? list
                    : list + "\nModels that need a source image (not generatable from this chat; the user starts them from /generations/new "
                    + "or a thumbnail's \"use as source\" button): " + needSource;
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

    @Tool(description = "List every user tag in use on generations and files (labels set by hand), alphabetically. Use it to suggest "
            + "an existing tag or to check one before searching by tag.")
    public String listTags() {
        try {
            List<String> tags = generations.allTags();
            return tags.isEmpty() ? "No tag is in use." : String.join(", ", tags);
        } catch (RuntimeException e) {
            return failure("listTags", e);
        }
    }

    @Tool(description = "Get the details of one generation or imported image by id: kind, status, model, prompt (for an imported "
            + "image, its description), tags, source, main settings, estimated cost, the exact file names (with favourite flag, "
            + "tags and reproducible seed of each) and, if it failed, the error. Opens at /generations/{id} (imported: /import/{id}).")
    public String getGeneration(@ToolParam(description = "The generation id, without the hash") Long id) {
        if (id == null) {
            return "Missing generation id.";
        }
        try {
            return generations.find(id).map(this::describe).orElse("No generation with id " + id + ".");
        } catch (RuntimeException e) {
            return failure("getGeneration", e);
        }
    }

    @Tool(description = "List what was generated in THIS conversation (most recent last, at most " + MAX_ITEMS + "): generation id, "
            + "file name, tags and prompt, plus the generations still running. Use it for questions like \"what have we made so far?\" "
            + "or to get the id of a generation just started.")
    public String conversationGallery(ToolContext toolContext) {
        try {
            if (!(toolContext.getContext().get(CONVERSATION_ID_CONTEXT_KEY) instanceof Long conversationId)) {
                return "The current conversation is unknown.";
            }
            List<GalleryItem> items = generations.succeededItemsForConversation(conversationId);
            List<Generation> running = generations.inProgressForConversation(conversationId);
            if (items.isEmpty() && running.isEmpty()) {
                return "Nothing has been generated in this conversation yet.";
            }
            List<GalleryItem> shown = items.size() > MAX_ITEMS ? items.subList(items.size() - MAX_ITEMS, items.size()) : items;
            String lines = shown.stream().map(item -> "- #%d %s%s%s: %s".formatted(item.generation().getId(), item.filename(),
                            item.generation().getFavouriteFilenames().contains(item.filename()) ? " (favourite)" : "",
                            tagsOf(item.generation(), item.filename()),
                            oneLine(item.generation().getPrompt(), PROMPT_SNIPPET)))
                    .collect(Collectors.joining("\n"));
            String inProgress = running.stream().map(g -> "- #%d (in progress, %s): %s".formatted(g.getId(), g.getStatus(),
                    oneLine(g.getPrompt(), PROMPT_SNIPPET))).collect(Collectors.joining("\n"));
            String result = Stream.of(items.size() > shown.size() ? "(showing the last %d of %d files)".formatted(shown.size(), items.size()) : "",
                    lines, inProgress).filter(part -> !part.isEmpty()).collect(Collectors.joining("\n"));
            return result;
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

    /** " [tags: a, b]" con i tag della generazione e quelli del file (senza doppioni), o stringa vuota. */
    private static String tagsOf(Generation g, String filename) {
        Set<String> tags = new TreeSet<>(g.getTags());
        tags.addAll(g.tagsOf(filename));
        return tags.isEmpty() ? "" : " [tags: " + String.join(", ", tags) + "]";
    }

    /** Impostazioni non segrete della generazione, dal JSON salvato (solo le chiavi di {@link #VISIBLE_PARAMETERS}). */
    private String settingsOf(Generation g) {
        if (blank(g.getParametersJson())) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(g.getParametersJson());
            return VISIBLE_PARAMETERS.stream().filter(key -> node.hasNonNull(key) && node.get(key).isValueNode())
                    .map(key -> key + "=" + node.get(key).asString()).collect(Collectors.joining(", "));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String describe(Generation g) {
        // Un'immagine importata non ha un modello ne' un prompt: ha una descrizione prodotta dall'analisi (vuota finche' non c'e').
        StringBuilder out = new StringBuilder(g.isImported()
                ? "Generation #%d: imported image (not generated, no model), status %s".formatted(g.getId(), g.getStatus())
                : "Generation #%d: %s, status %s, model %s".formatted(g.getId(), g.getKind(), g.getStatus(), g.getModel()));
        out.append(g.isImported() ? "\nDescription: " : "\nPrompt: ").append(oneLine(g.getPrompt(), 600));
        if (!g.getTags().isEmpty()) {
            out.append("\nTags: ").append(String.join(", ", new TreeSet<>(g.getTags())));
        }
        if (g.isImported() && g.getAnalysisStatus() != null) {
            // I tag AI sono vocabolario dell'analisi, non i tag dell'utente: etichetta distinta.
            out.append("\nContent analysis: ").append(g.getAnalysisStatus());
            if (!g.getAnalysisTagList().isEmpty()) {
                out.append(" (AI keywords: ").append(String.join(", ", g.getAnalysisTagList())).append(")");
            }
        }
        if (g.getSourceGenerationId() != null) {
            out.append("\nStarted from an image of generation #").append(g.getSourceGenerationId());
        } else if (g.getSourceUploadFilename() != null) {
            out.append("\nStarted from an image uploaded by the user");
        }
        if (g.getMaskUploadFilename() != null) {
            out.append(" (with a painted mask)");
        }
        String settings = settingsOf(g);
        if (!settings.isEmpty()) {
            out.append("\nSettings: ").append(settings);
        }
        out.append("\nFiles: %d of %d requested".formatted(g.getImageFilenames().size(), g.getRequestedOutputs()));
        for (String filename : g.getImageFilenames()) {
            Long seed = g.reusableSeedOf(filename);
            out.append("\n- ").append(filename);
            if (g.getFavouriteFilenames().contains(filename)) {
                out.append(" (favourite)");
            }
            if (!g.tagsOf(filename).isEmpty()) {
                out.append(" [tags: ").append(String.join(", ", g.tagsOf(filename))).append("]");
            }
            out.append(g.isImported() ? "" : seed != null ? ", seed " + seed : ", no reproducible seed");
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

    @Override
    public String promptSection() {
        return "deep-chat.section.library";
    }
}
