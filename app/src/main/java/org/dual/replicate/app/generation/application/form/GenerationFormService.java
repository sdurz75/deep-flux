package org.dual.replicate.app.generation.application.form;

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
import org.springframework.stereotype.Service;

/**
 * Use case del binding delle form dei parametri di generazione ({@link IGenerationForms}): risolve quale
 * {@link IGenerationParameterHandler} gestisce un dato {@link GenerationFormType} (le implementazioni sono bean Spring
 * auto-raccolti, uno per form-type: aggiungere una form significa aggiungere un handler col proprio
 * {@link IGenerationParameterHandler#formType()}, nient'altro da toccare qui) e converte i campi sottomessi (stringhe: un submit
 * HTML o il JSON della chat) nei parametri del provider. Lo usano la form diretta di /generations e il pannello di /deep-chat,
 * cosi' la conversione e' una sola. Del fragment Thymeleaf che renderizza i campi si occupa l'adapter web.
 */
@Service
public class GenerationFormService implements IGenerationForms {

    private final Map<GenerationFormType, IGenerationParameterHandler> byFormType;
    private final IApiTokens tokens;
    private final ILoraPresets loraPresets;

    public GenerationFormService(List<IGenerationParameterHandler> handlers, IApiTokens tokens, ILoraPresets loraPresets) {
        this.byFormType = handlers.stream()
                .collect(Collectors.toMap(IGenerationParameterHandler::formType, Function.identity()));
        this.tokens = tokens;
        this.loraPresets = loraPresets;
    }

    /** L'handler del form-type (un'implementazione per form-type). */
    IGenerationParameterHandler handler(GenerationFormType formType) {
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
    @Override
    public Map<String, Object> extraFormOptions(GenerationFormType formType) {
        if (formType == GenerationFormType.FLUX_FILL_DEV || formType == GenerationFormType.FLUX_LORA_FINETUNE) {
            // Nessun token (il modello non ha hf_api_token/civitai_api_token): solo i preset anagrafati.
            return new LinkedHashMap<>(loraPresets.formOptions());
        }
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
    public Map<String, Object> defaultFields(GenerationFormType formType) {
        return handler(formType).defaultFields();
    }

    @Override
    public Map<String, Object> parameters(GenerationFormType formType, Map<String, String> submittedFields) {
        return handler(formType).toParameterMap(submittedFields);
    }

    @Override
    public Map<String, String> formFields(GenerationFormType formType, Map<String, Object> parameters) {
        return handler(formType).toFormFields(parameters);
    }

    @Override
    public Map<String, Object> formModel(GenerationFormType formType) {
        Map<String, Object> model = new LinkedHashMap<>(defaultFields(formType));
        model.putAll(extraFormOptions(formType));
        model.put(IGenerationForms.FIELD_DEFAULTS, defaultFields(formType));
        return model;
    }
}
