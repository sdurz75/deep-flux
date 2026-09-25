package org.dual.replicate.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import org.dual.replicate.domain.Generation;
import org.dual.replicate.domain.GenerationStatus;
import org.dual.replicate.replicate.ReplicateException;
import org.dual.replicate.replicate.ReplicateModelCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Tool Spring AI registrato sul ChatClient di DeepChatService: genera
 * un'immagine su Replicate (riusando GenerationService, la stessa
 * orchestrazione di GenerationController), attende sincronamente il
 * risultato e lo comunica al modello. Il file viene sempre salvato
 * tramite GenerationService/ImageStorageService come per il resto
 * dell'app: DeepChatService legge poi il risultato da ToolContext per
 * farlo comparire come immagine nella risposta di /api/deep-chat.
 */
@Component
public class ImageGenerationTool {

    /**
     * Chiave ToolContext sotto cui DeepChatService mette i parametri di
     * generazione impostati nel pannello UI (aspect_ratio, width,
     * height, ...): caller -> tool, mai visti dal modello LLM (a
     * differenza del modello scelto, sono impostazioni deterministiche,
     * non vogliamo che l'LLM le riscriva componendo la chiamata al tool).
     */
    public static final String PARAMETERS_CONTEXT_KEY = "generationParameters";

    /**
     * Tetto per non bloccare troppo a lungo la richiesta HTTP di
     * /api/deep-chat: piu' breve del timeout di 5 minuti usato da
     * GenerationService per marcare una generazione FAILED (quello resta
     * il ciclo di vita "vero" della generazione, consultabile via
     * /generations/{id} anche se questa chiamata scade prima).
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(90);

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

    @Tool(description = "Generate an image from a text prompt using Replicate; the image is stored and "
            + "shown to the user automatically, you don't need to include its URL in your reply. "
            + "The 'model' parameter must be one of the available models (in \"owner/name\" form); if you "
            + "are not sure of the exact id, try the one you were told is currently selected in the UI.")
    public String generateImage(
            @ToolParam(description = "Detailed, self-contained prompt describing the desired image, in English") String prompt,
            @ToolParam(description = "Replicate model to use, in \"owner/name\" form") String model,
            ToolContext toolContext) {
        if (!modelCatalog.contains(model)) {
            return "Modello \"%s\" non disponibile. Modelli disponibili: %s".formatted(model, modelCatalog.idsAsCsv());
        }

        // Passare esplicitamente la versione (se nota) invece di lasciare
        // che ReplicateClient usi lo shortcut "ultima versione": non tutti
        // i modelli lo supportano, vedi ReplicateModelCatalog.latestVersionOf.
        String version = modelCatalog.latestVersionOf(model).orElse(null);
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
        generation = generationService.waitUntilTerminal(generation.getId(), WAIT_TIMEOUT);

        if (generation.getStatus() == GenerationStatus.SUCCEEDED) {
            Object holder = toolContext.getContext().get(GenerationResultHolder.CONTEXT_KEY);
            if (holder instanceof GenerationResultHolder resultHolder) {
                resultHolder.setGeneration(generation);
            }
            return "Immagine generata con successo con il modello " + model + ".";
        }
        if (generation.getStatus() == GenerationStatus.FAILED) {
            return "Generazione fallita: " + generation.getErrorMessage();
        }
        return "La generazione e' ancora in corso, ci sta mettendo piu' del previsto. "
                + "Stato consultabile su /generations/" + generation.getId() + ".";
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
