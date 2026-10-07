package org.dual.hexa.app.chat.adapter.ai;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dual.hexa.ai.chat.domain.ChatTurnContext;
import org.dual.hexa.ai.chat.port.in.IChatTurnContributor;
import org.dual.hexa.app.generation.port.in.IGenerationForms;
import org.dual.hexa.app.generation.port.in.IModelCatalog;
import org.springframework.stereotype.Component;

/**
 * Lato app del contesto di un turno: il modello e i parametri del pannello impostazioni della UI ({@code settings.model} e
 * {@code settings.parameters} del client) diventano le chiavi del {@code ToolContext} che legge {@code ImageGenerationTool}: il modello e'
 * SEMPRE quello scelto, mai deciso dall'LLM, e i parametri (gia' nel vocabolario Replicate) non arrivano mai al modello LLM. Il modello
 * scelto e' anche una nota di sistema per l'assistente.
 */
@Component
class GenerationChatTurnContext implements IChatTurnContributor {

    private final IModelCatalog modelCatalog;
    private final IGenerationForms forms;

    GenerationChatTurnContext(IModelCatalog modelCatalog, IGenerationForms forms) {
        this.modelCatalog = modelCatalog;
        this.forms = forms;
    }

    @Override
    public Map<String, Object> contribute(Map<String, Object> clientSettings) {
        String model = clientSettings.get("model") instanceof String s ? s : null;
        Map<String, Object> context = new HashMap<>();
        context.put(ImageGenerationTool.PARAMETERS_CONTEXT_KEY, toGenerationParameters(model, clientSettings.get("parameters")));
        if (model != null) {
            context.put(ImageGenerationTool.MODEL_CONTEXT_KEY, model);
        }
        if (model != null && !model.isBlank()) {
            context.put(ChatTurnContext.SYSTEM_NOTES, List.of("Image generation model currently selected in the UI: " + model));
        }
        return context;
    }

    /**
     * Delega alla porta {@link IGenerationForms} per il form-type del modello scelto (stesso binding del form diretto di
     * GenerationController): se il modello non e' censito nel catalogo nessun parametro extra viene inviato. I valori arrivano dal client
     * gia' come stringhe, stesso formato di un submit HTML.
     */
    private Map<String, Object> toGenerationParameters(String model, Object parameters) {
        return modelCatalog.formTypeOf(model)
                .map(formType -> forms.parameters(formType, toStringMap(parameters)))
                .orElseGet(Map::of);
    }

    private static Map<String, String> toStringMap(Object parameters) {
        Map<String, String> submitted = new LinkedHashMap<>();
        if (parameters instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    submitted.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return submitted;
    }
}
