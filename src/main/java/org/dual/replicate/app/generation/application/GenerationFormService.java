package org.dual.replicate.app.generation.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.port.in.IGenerationForms;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.app.generation.port.in.IGenerationParameterHandler;
import org.springframework.stereotype.Component;

/**
 * Risolve quale {@link IGenerationParameterHandler} gestisce un dato
 * {@link GenerationFormType}: le implementazioni sono bean Spring
 * auto-raccolte (una per form-type), cosi' nessuno dei tre chiamanti
 * (GenerationController, DeepChatController, DeepChatApiController) deve
 * ripetere il proprio dispatch — aggiungere una form significa aggiungere
 * un nuovo {@link IGenerationParameterHandler} col proprio
 * {@link IGenerationParameterHandler#formType()}, nient'altro da toccare
 * qui.
 */
@Component
public class GenerationFormService implements IGenerationForms {

    private final Map<GenerationFormType, IGenerationParameterHandler> byFormType;
    private final TokenInputResolver tokens;
    private final ILoraPresets loraPresets;

    public GenerationFormService(List<IGenerationParameterHandler> handlers, TokenInputResolver tokens, ILoraPresets loraPresets) {
        this.byFormType = handlers.stream()
                .collect(Collectors.toMap(IGenerationParameterHandler::formType, Function.identity()));
        this.tokens = tokens;
        this.loraPresets = loraPresets;
    }

    @Override
    public Map<String, Object> extraFormOptions(GenerationFormType formType) {
        if (formType != GenerationFormType.FLUX_DEV_LORA) {
            return Map.of();
        }
        // Le select dei token e dei LoRA anagrafati: solo dove si renderizza il fragment di flux-dev-lora, mai a ogni richiesta.
        Map<String, Object> options = new java.util.LinkedHashMap<>(tokens.formOptions());
        options.putAll(loraPresets.formOptions());
        return options;
    }

    @Override
    public IGenerationParameterHandler get(GenerationFormType formType) {
        IGenerationParameterHandler handler = byFormType.get(formType);
        if (handler == null) {
            throw new IllegalStateException("Nessun IGenerationParameterHandler registrato per " + formType);
        }
        return handler;
    }
}
