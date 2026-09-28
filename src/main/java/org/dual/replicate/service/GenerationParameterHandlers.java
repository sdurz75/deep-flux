package org.dual.replicate.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dual.replicate.domain.GenerationFormType;
import org.springframework.stereotype.Component;

/**
 * Risolve quale {@link GenerationParameterHandler} gestisce un dato
 * {@link GenerationFormType}: le implementazioni sono bean Spring
 * auto-raccolte (una per form-type), cosi' nessuno dei tre chiamanti
 * (GenerationController, DeepChatController, DeepChatApiController) deve
 * ripetere il proprio dispatch — aggiungere una form significa aggiungere
 * un nuovo {@link GenerationParameterHandler} col proprio
 * {@link GenerationParameterHandler#formType()}, nient'altro da toccare
 * qui.
 */
@Component
public class GenerationParameterHandlers {

    private final Map<GenerationFormType, GenerationParameterHandler> byFormType;

    public GenerationParameterHandlers(List<GenerationParameterHandler> handlers) {
        this.byFormType = handlers.stream()
                .collect(Collectors.toMap(GenerationParameterHandler::formType, Function.identity()));
    }

    public GenerationParameterHandler get(GenerationFormType formType) {
        GenerationParameterHandler handler = byFormType.get(formType);
        if (handler == null) {
            throw new IllegalStateException("Nessun GenerationParameterHandler registrato per " + formType);
        }
        return handler;
    }
}
