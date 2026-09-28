package org.dual.replicate.service;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.ReplicateModel;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool Spring AI registrato sul ChatClient di DeepChatService: avvia una
 * generazione immagine su Replicate (riusando GenerationService, la
 * stessa orchestrazione di GenerationController) e torna subito, senza
 * attenderne l'esito. Il file viene sempre salvato tramite
 * GenerationService/ImageStorageService come per il resto dell'app;
 * l'esito arriva in un secondo momento in modo asincrono (poll in
 * background + push SSE, vedi DeepChatGenerationWatcher, avviato da
 * DeepChatService.reply usando gli id raccolti qui in
 * GenerationResultHolder), non da questo metodo.
 */
@Component
public class ImageGenerationTool {

    /**
     * Chiave ToolContext sotto cui DeepChatService mette i parametri di
     * generazione impostati nel pannello UI (aspect_ratio, width,
     * height, ...): caller -> tool, mai visti dal modello LLM, sono
     * impostazioni deterministiche, non vogliamo che l'LLM le riscriva
     * componendo la chiamata al tool.
     */
    public static final String PARAMETERS_CONTEXT_KEY = "generationParameters";

    /**
     * Chiave ToolContext sotto cui DeepChatService mette il modello
     * Replicate scelto nel combobox lato UI: per ora (vedi CLAUDE.md,
     * Scopo punto 1) il tool usa sempre e solo questo, non un parametro
     * scelto dall'LLM — al modello non interessa quale sia, la scelta
     * resta interamente lato UI, come per PARAMETERS_CONTEXT_KEY sopra.
     */
    public static final String MODEL_CONTEXT_KEY = "selectedModel";

    private final GenerationService generationService;
    private final ReplicateModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;

    public ImageGenerationTool(GenerationService generationService,
                                ReplicateModelCatalog modelCatalog,
                                ObjectMapper objectMapper) {
        this.generationService = generationService;
        this.modelCatalog = modelCatalog;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Generate an image from a text prompt using Replicate. This starts the generation and "
            + "returns immediately, before the image is ready: it will be stored and shown to the user "
            + "automatically once done (usually within a couple of minutes), in this conversation and in the "
            + "gallery. Tell the user it's being generated, don't claim it's already available. You don't need "
            + "to include its URL in your reply. Always uses the model currently selected in the UI - there is "
            + "no way to pick a different one here.")
    public String generateImage(
            @ToolParam(description = "Detailed, self-contained prompt describing the desired image, in English") String prompt,
            ToolContext toolContext) {
        String model = resolveModel(toolContext);
        if (model == null) {
            return "Nessun modello Replicate censito nel catalogo, impossibile avviare la generazione.";
        }

        // Passare esplicitamente la versione (se nota) invece di lasciare
        // che ReplicateClient usi lo shortcut "ultima versione": non tutti
        // i modelli lo supportano, vedi ReplicateModelCatalog.versionOf.
        String version = modelCatalog.versionOf(model).orElse(null);
        String parametersJson = buildParametersJson(toolContext);
        Generation generation;
        try {
            generation = generationService.create(model, version, prompt, parametersJson);
        } catch (ReplicateException e) {
            // Es. troppe generazioni gia' in corso su Replicate: rifiuto
            // applicativo, non un errore di rete. Restituirlo come testo
            // invece di propagarlo fa si' che diventi la risposta del
            // modello, persistita come ogni altro turno della
            // conversazione (a differenza del catch-all di
            // DeepChatApiController, che non persiste nulla).
            return "Impossibile avviare la generazione: " + e.getMessage() + " Non ritentare automaticamente.";
        }

        Object holder = toolContext.getContext().get(GenerationResultHolder.CONTEXT_KEY);
        if (holder instanceof GenerationResultHolder resultHolder) {
            resultHolder.addStartedGeneration(generation.getId());
        }
        return "Generazione avviata con il modello " + model + ": l'immagine comparira' "
                + "automaticamente in questa conversazione e in Galleria non appena pronta.";
    }

    /**
     * Modello da usare: sempre quello passato da DeepChatService sotto
     * MODEL_CONTEXT_KEY (il combobox lato UI, vedi il suo Javadoc
     * sopra), col catalogo come unica rete di sicurezza — un id non piu'
     * censito (rimosso nel frattempo) o un context mancante (non
     * dovrebbe succedere, ma il ToolContext non e' tipizzato) ricadono
     * sul modello di default, mai su un valore arbitrario scelto qui.
     */
    private String resolveModel(ToolContext toolContext) {
        Object fromContext = toolContext.getContext().get(MODEL_CONTEXT_KEY);
        if (fromContext instanceof String model && modelCatalog.contains(model)) {
            return model;
        }
        return modelCatalog.defaultModel().map(ReplicateModel::getIdentifier).orElse(null);
    }

    /**
     * Unisce i parametri impostati nel pannello UI (se presenti nel
     * ToolContext) con disable_safety_checker, sempre true e non
     * esposto in UI: nessun modo per l'utente di disattivarlo.
     */
    private String buildParametersJson(ToolContext toolContext) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object fromContext = toolContext.getContext().get(PARAMETERS_CONTEXT_KEY);
        if (fromContext instanceof Map<?, ?> map) {
            map.forEach((key, value) -> params.put(String.valueOf(key), value));
        }
        params.put("disable_safety_checker", true);
        return objectMapper.writeValueAsString(params);
    }
}
