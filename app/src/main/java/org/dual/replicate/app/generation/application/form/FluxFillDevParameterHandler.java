package org.dual.replicate.app.generation.application.form;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

import static org.dual.replicate.app.shared.domain.FormFields.asDouble;
import static org.dual.replicate.app.shared.domain.FormFields.asInteger;
import static org.dual.replicate.app.shared.domain.FormFields.asLong;
import static org.dual.replicate.app.shared.domain.FormFields.asOneOf;
import static org.dual.replicate.app.shared.domain.FormFields.asText;
import static org.dual.replicate.app.shared.domain.FormFields.putIfPresent;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_FILL_DEV}: i campi di input di
 * black-forest-labs/flux-fill-dev (inpainting; schema letto da Replicate il 2026-10-02). {@code image} (sorgente) e
 * {@code mask} (bianco = zona da ridipingere) non sono campi di questo form: le aggiunge {@code GenerationService} sotto
 * {@link GenerationFormType#sourceImageParam()}/{@link GenerationFormType#maskParam()}. {@code disable_safety_checker} lo forza il service.
 * <p>
 * UN solo LoRA ({@code lora_weights}/{@code lora_scale}: Replicate {@code owner/name[/version]}, URL HuggingFace/CivitAI o un
 * .safetensors) e nessun token: il modello non ha {@code extra_lora} ne' {@code hf_api_token}, quindi niente LoRA privati.
 * {@code megapixels=match_input} (default) lavora alla dimensione della sorgente, fino a 1440x1440.
 */
@Component
public class FluxFillDevParameterHandler implements IGenerationParameterHandler {

    public static final String MATCH_INPUT = "match_input";
    public static final Set<String> MEGAPIXELS = Set.of("1", "0.25", MATCH_INPUT);
    public static final Set<String> OUTPUT_FORMATS = Set.of("webp", "jpg", "png");

    public static final String DEFAULT_MEGAPIXELS = MATCH_INPUT;
    public static final double DEFAULT_GUIDANCE = 30;
    public static final int DEFAULT_STEPS = 28;
    public static final int MIN_STEPS = 1;
    public static final int MAX_STEPS = 50;
    public static final double DEFAULT_LORA_SCALE = 1;
    public static final double MIN_LORA_SCALE = -1;
    public static final double MAX_LORA_SCALE = 3;
    public static final int DEFAULT_NUM_OUTPUTS = 1;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_OUTPUT_QUALITY = 80;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_FILL_DEV;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "lora_weights", asText(submittedFields.get("lora_weights")));
        Double loraScale = asDouble(submittedFields.get("lora_scale"));
        if (loraScale != null) {
            params.put("lora_scale", Math.max(MIN_LORA_SCALE, Math.min(MAX_LORA_SCALE, loraScale)));
        }
        putIfPresent(params, "megapixels", asOneOf(submittedFields.get("megapixels"), MEGAPIXELS));
        Integer steps = asInteger(submittedFields.get("num_inference_steps"));
        if (steps != null) {
            params.put("num_inference_steps", Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps)));
        }
        Double guidance = asDouble(submittedFields.get("guidance"));
        if (guidance != null) {
            params.put("guidance", Math.max(0, Math.min(100, guidance)));
        }
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        putIfPresent(params, "num_outputs", asNumOutputs(submittedFields.get("num_outputs")));
        putIfPresent(params, "output_format", asOneOf(submittedFields.get("output_format"), OUTPUT_FORMATS));
        Integer quality = asInteger(submittedFields.get("output_quality"));
        if (quality != null) {
            params.put("output_quality", Math.max(0, Math.min(100, quality)));
        }
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        // Vuoto = nessun LoRA (FLUX Fill puro); la chiave serve perche' populateFormTypeFields conserva solo le chiavi dei default.
        defaults.put("lora_weights", "");
        defaults.put("lora_scale", DEFAULT_LORA_SCALE);
        defaults.put("megapixels", DEFAULT_MEGAPIXELS);
        defaults.put("num_inference_steps", DEFAULT_STEPS);
        defaults.put("guidance", DEFAULT_GUIDANCE);
        defaults.put("num_outputs", DEFAULT_NUM_OUTPUTS);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        defaults.put("output_quality", DEFAULT_OUTPUT_QUALITY);
        // "seed" intenzionalmente assente: casuale di default.
        return defaults;
    }
}
