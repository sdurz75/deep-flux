package org.dual.replicate.app.chat.adapter.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Tool Spring AI registrato sul ChatClient di SpringAiAssistant: avvia UNA
 * predizione di generazione su Replicate (riusando IGenerations, la
 * stessa orchestrazione di GenerationController; i file attesi li decide
 * il pannello della UI, fino a 4) e torna subito, con l'id della
 * generazione, senza attenderne l'esito. E' l'unico tool a pagamento:
 * il numero per turno e' limitato da {@code app.chat.max-generations-per-turn}. Il file viene sempre salvato tramite
 * IGenerations/IImageStorageService come per il resto dell'app;
 * l'esito arriva in un secondo momento in modo asincrono (poll in
 * background + push SSE, vedi ChatGenerationWatcher, avviato da
 * ChatService#reply usando gli id raccolti qui in
 * GenerationResultHolder), non da questo metodo.
 */
@Component
@Order(100)
public class ImageGenerationTool implements ChatToolkit {

    /**
     * Chiave ToolContext sotto cui SpringAiAssistant mette i parametri di
     * generazione impostati nel pannello UI (aspect_ratio, width,
     * height, ...): caller -> tool, mai visti dal modello LLM, sono
     * impostazioni deterministiche, non vogliamo che l'LLM le riscriva
     * componendo la chiamata al tool.
     */
    public static final String PARAMETERS_CONTEXT_KEY = "generationParameters";

    /**
     * Chiave ToolContext sotto cui SpringAiAssistant mette il modello
     * Replicate scelto nel combobox lato UI: per ora (vedi CLAUDE.md,
     * Scopo punto 1) il tool usa sempre e solo questo, non un parametro
     * scelto dall'LLM — al modello non interessa quale sia, la scelta
     * resta interamente lato UI, come per PARAMETERS_CONTEXT_KEY sopra.
     */
    public static final String MODEL_CONTEXT_KEY = "selectedModel";

    private final IGenerations generationService;
    private final IModelCatalog modelCatalog;
    private final ISystemEvents systemEvents;
    private final int maxGenerationsPerTurn;

    public ImageGenerationTool(IGenerations generationService,
                                IModelCatalog modelCatalog,
                                ISystemEvents systemEvents,
                                @Value("${app.chat.max-generations-per-turn:3}") int maxGenerationsPerTurn) {
        this.systemEvents = systemEvents;
        this.generationService = generationService;
        this.modelCatalog = modelCatalog;
        this.maxGenerationsPerTurn = maxGenerationsPerTurn;
    }

    @Tool(description = "Generate an image from a text prompt using Replicate. This starts ONE paid generation (the UI "
            + "settings decide how many files it yields, up to 4) and returns its id immediately, before the image is ready: "
            + "the result is stored and shown to the user automatically once done (usually within a couple of minutes), in "
            + "this conversation and in the gallery. Tell the user it is being generated and refer to it as #id; never claim "
            + "it is already available. Call it only after the user confirmed the exact prompt. Always uses the model "
            + "currently selected in the UI - there is no way to pick a different one here.")
    public String generateImage(
            @ToolParam(description = "Detailed, self-contained prompt describing the desired image, in English") String prompt,
            ToolContext toolContext) {
        if (prompt == null || prompt.isBlank()) {
            return "Not started: the prompt is empty. Ask the user what to generate.";
        }
        Object holder = toolContext.getContext().get(GenerationResultHolder.CONTEXT_KEY);
        GenerationResultHolder resultHolder = holder instanceof GenerationResultHolder found ? found : null;
        // Ogni chiamata e' una predizione a pagamento: un tetto per turno ferma un modello che si mette a ripetere il tool.
        if (resultHolder != null && resultHolder.getStartedGenerationIds().size() >= maxGenerationsPerTurn) {
            return "Not started: " + maxGenerationsPerTurn + " generations were already started in this turn, which is the limit. "
                    + "Tell the user and wait for their next request.";
        }
        String model = resolveModel(toolContext);
        if (model == null) {
            return "No Replicate model is configured in the catalog: the generation cannot be started.";
        }

        // Passare esplicitamente la versione (se nota) invece di lasciare
        // che ReplicateClient usi lo shortcut "ultima versione": non tutti
        // i modelli lo supportano, vedi IModelCatalog.versionOf.
        String version = modelCatalog.versionOf(model).orElse(null);
        Generation generation;
        try {
            generation = generationService.create(IGenerations.CreateCommand.of(model, version, prompt, parameters(toolContext)));
        } catch (ReplicateException e) {
            systemEvents.record("createPrediction", e);
            // Es. troppe generazioni gia' in corso su Replicate: rifiuto
            // applicativo, non un errore di rete. Restituirlo come testo
            // invece di propagarlo fa si' che diventi la risposta del
            // modello, persistita come ogni altro turno della
            // conversazione (a differenza del catch-all di
            // DeepChatApiController, che non persiste nulla).
            return "Could not start the generation: " + e.getMessage() + " Do not retry automatically.";
        } catch (RuntimeException e) {
            // Errore inatteso (parametri non serializzabili, DB...): stesso trattamento, mai un'eccezione
            // che attraversi Spring AI con esito non verificato.
            systemEvents.record(CoreEventSource.INTERNAL, "generateImage", e);
            return "Could not start the generation: internal error (" + ISystemEvents.sanitize(e)
                    + "). Do not retry automatically.";
        }

        if (resultHolder != null) {
            resultHolder.addStartedGeneration(generation.getId());
        }
        return "Generation #%d started with model %s (%d file(s) requested). It runs in the background: the result will appear in "
                .formatted(generation.getId(), model, generation.getRequestedOutputs())
                + "this conversation and in the gallery as soon as it is ready. Tell the user it is being generated and refer to it as #"
                + generation.getId() + ".";
    }

    /**
     * Modello da usare: sempre quello passato da SpringAiAssistant sotto
     * MODEL_CONTEXT_KEY (il combobox lato UI, vedi il suo Javadoc
     * sopra), col catalogo come unica rete di sicurezza — un id non piu'
     * censito (rimosso nel frattempo) o un context mancante (non
     * dovrebbe succedere, ma il ToolContext non e' tipizzato) ricadono
     * sul modello di default, mai su un valore arbitrario scelto qui.
     */
    private String resolveModel(ToolContext toolContext) {
        Object fromContext = toolContext.getContext().get(MODEL_CONTEXT_KEY);
        if (fromContext instanceof String model && modelCatalog.contains(model, GenerationKind.IMAGE)) {
            return model;
        }
        return modelCatalog.defaultModel().map(ReplicateModel::getIdentifier).orElse(null);
    }

    /**
     * I parametri impostati nel pannello UI (se presenti nel ToolContext), gia' convertiti da DeepChatApiController.
     * disable_safety_checker NON va forzato qui: lo fa IGenerations#create per ogni chiamante, form diretto incluso -
     * duplicarlo qui varrebbe solo per questo tool, lasciando scoperto l'altro percorso.
     */
    private Map<String, Object> parameters(ToolContext toolContext) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object fromContext = toolContext.getContext().get(PARAMETERS_CONTEXT_KEY);
        if (fromContext instanceof Map<?, ?> map) {
            map.forEach((key, value) -> params.put(String.valueOf(key), value));
        }
        return params;
    }

    @Override
    public String promptSection() {
        return "deep-chat.section.generation";
    }
}
