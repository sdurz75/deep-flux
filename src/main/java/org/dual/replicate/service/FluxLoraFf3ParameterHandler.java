package org.dual.replicate.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.domain.GenerationFormType;
import org.springframework.stereotype.Component;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_LORA_FF3}: gli
 * stessi 9 campi tipizzati della vecchia form "generica" condivisa da
 * tutti i modelli (aspect_ratio fisso a "custom", width, height,
 * output_format, num_inference_steps, guidance_scale, seed, lora_scale,
 * flux_model, num_outputs), solo spostati qui e riletti da una
 * {@code Map<String,String>} di campi sottomessi invece che da parametri
 * Java tipizzati — nessun campo e' cambiato, e' un rename/reshape, non
 * una riprogettazione (vedi il piano di questa feature).
 *
 * {@code flux_model} finisce nella mappa sotto la chiave "model": e' il
 * campo che questo fine-tune LoRA di Flux accetta nel suo input per
 * scegliere il checkpoint base ("dev" o "schnell") su cui girare
 * l'inferenza — non ha nulla a che vedere con l'"owner/name" del modello
 * Replicate scelto nel combobox (quello resta un parametro Java separato,
 * passato a parte a GenerationService.create). La stessa chiave "model"
 * per due concetti diversi e' solo una coincidenza del vocabolario di
 * Replicate, non un collegamento nel codice.
 */
@Component
public class FluxLoraFf3ParameterHandler implements GenerationParameterHandler {

    public static final int DEFAULT_WIDTH = 848;
    public static final int DEFAULT_HEIGHT = 1280;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_NUM_INFERENCE_STEPS = 28;
    public static final double DEFAULT_GUIDANCE_SCALE = 3;
    public static final double DEFAULT_LORA_SCALE = 1;
    public static final String DEFAULT_FLUX_MODEL = "dev";
    public static final int DEFAULT_NUM_OUTPUTS = 1;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_LORA_FF3;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", submittedFields.get("aspect_ratio"));
        putIfPresent(params, "width", asInteger(submittedFields.get("width")));
        putIfPresent(params, "height", asInteger(submittedFields.get("height")));
        putIfPresent(params, "output_format", submittedFields.get("output_format"));
        putIfPresent(params, "num_inference_steps", asInteger(submittedFields.get("num_inference_steps")));
        putIfPresent(params, "guidance_scale", asDouble(submittedFields.get("guidance_scale")));
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        putIfPresent(params, "lora_scale", asDouble(submittedFields.get("lora_scale")));
        putIfPresent(params, "model", submittedFields.get("flux_model"));
        putIfPresent(params, "num_outputs", asInteger(submittedFields.get("num_outputs")));
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("num_outputs", DEFAULT_NUM_OUTPUTS);
        defaults.put("width", DEFAULT_WIDTH);
        defaults.put("height", DEFAULT_HEIGHT);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        defaults.put("flux_model", DEFAULT_FLUX_MODEL);
        defaults.put("num_inference_steps", DEFAULT_NUM_INFERENCE_STEPS);
        defaults.put("guidance_scale", DEFAULT_GUIDANCE_SCALE);
        defaults.put("lora_scale", DEFAULT_LORA_SCALE);
        // "seed" intenzionalmente assente: il default e' vuoto/casuale
        // (placeholder "casuale" nel fragment), non un valore censito.
        return defaults;
    }

    @Override
    public String fragmentName() {
        return "fragments/generation-params-flux-lora-ff3 :: fields";
    }

    private static void putIfPresent(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }

    private static Integer asInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
