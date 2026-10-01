package org.dual.replicate.app.generation.adapter.in.web.form;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dual.replicate.app.generation.domain.ApiTokenProvider;
import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.core.tokens.port.in.IApiTokens;
import org.springframework.stereotype.Component;

/**
 * Risolve quale {@link IGenerationParameterHandler} gestisce un dato
 * {@link GenerationFormType}: le implementazioni sono bean Spring
 * auto-raccolte (una per form-type), cosi' nessuno dei chiamanti
 * (GenerationController, DeepChatController, DeepChatApiController) deve
 * ripetere il proprio dispatch — aggiungere una form significa aggiungere
 * un nuovo {@link IGenerationParameterHandler} col proprio
 * {@link IGenerationParameterHandler#formType()}, nient'altro da toccare
 * qui.
 * <p>
 * Vive nell'adapter web: la conversione dei campi di una form (stringhe) nei
 * parametri del provider e' un compito dell'interface layer, non dell'esagono.
 * Fuori dall'adapter (la chat) si vede solo la porta {@link IGenerationForms}.
 */
@Component
public class GenerationFormRegistry implements IGenerationForms {

    private final Map<GenerationFormType, IGenerationParameterHandler> byFormType;
    private final IApiTokens tokens;
    private final ILoraPresets loraPresets;

    public GenerationFormRegistry(List<IGenerationParameterHandler> handlers, IApiTokens tokens, ILoraPresets loraPresets) {
        this.byFormType = handlers.stream()
                .collect(Collectors.toMap(IGenerationParameterHandler::formType, Function.identity()));
        this.tokens = tokens;
        this.loraPresets = loraPresets;
    }

    /** L'handler del form-type (un'implementazione per form-type). */
    public IGenerationParameterHandler handler(GenerationFormType formType) {
        IGenerationParameterHandler handler = byFormType.get(formType);
        if (handler == null) {
            throw new IllegalStateException("Nessun IGenerationParameterHandler registrato per " + formType);
        }
        return handler;
    }

    /**
     * Attributi di Model aggiuntivi richiesti dal fragment del form-type (es. le select dei token e dei LoRA anagrafati per
     * flux-dev-lora): vuoti per gli altri, mai calcolati a ogni richiesta se il fragment non li usa.
     */
    public Map<String, Object> extraFormOptions(GenerationFormType formType) {
        if (formType != GenerationFormType.FLUX_DEV_LORA) {
            return Map.of();
        }
        // Le select dei token e dei LoRA anagrafati: solo dove si renderizza il fragment di flux-dev-lora, mai a ogni richiesta.
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("hfTokens", tokens.options(ApiTokenProvider.HUGGINGFACE.name()));
        options.put("civitaiTokens", tokens.options(ApiTokenProvider.CIVITAI.name()));
        options.putAll(loraPresets.formOptions());
        return options;
    }

    @Override
    public Map<String, Object> parameters(GenerationFormType formType, Map<String, String> submittedFields) {
        return handler(formType).toParameterMap(submittedFields);
    }

    @Override
    public Map<String, Object> formModel(GenerationFormType formType) {
        Map<String, Object> model = new LinkedHashMap<>(handler(formType).defaultFields());
        model.putAll(extraFormOptions(formType));
        return model;
    }
}
