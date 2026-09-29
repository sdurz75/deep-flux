package org.dual.replicate.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.domain.GenerationFormType;
import org.springframework.stereotype.Component;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_KONTEXT_DEV}: i campi
 * di input di black-forest-labs/flux-kontext-dev (schema da
 * replicate.com/.../api/schema, vedi migrazione V15). {@code input_image}
 * (obbligatorio) non e' un campo di questo form: lo aggiunge
 * GenerationService sotto {@link GenerationFormType#sourceImageParam()}.
 * {@code disable_safety_checker} e' forzato dal service per ogni immagine.
 *
 * {@code go_fast} e' una checkbox: va letta con {@code containsKey}, vedi
 * Flux2Klein9bParameterHandler. Il default e' {@code false} (scelta dell'utente, non il default Replicate) e il formato di default e' jpg.
 */
@Component
public class FluxKontextDevParameterHandler implements GenerationParameterHandler {

    public static final String MATCH_INPUT_IMAGE = "match_input_image";
    public static final Set<String> ASPECT_RATIOS = Set.of("1:1", "16:9", "21:9", "3:2", "2:3", "4:5", "5:4",
            "3:4", "4:3", "9:16", "9:21", MATCH_INPUT_IMAGE);
    public static final Set<String> OUTPUT_FORMATS = Set.of("webp", "jpg", "png");

    public static final String DEFAULT_ASPECT_RATIO = MATCH_INPUT_IMAGE;
    public static final double DEFAULT_GUIDANCE = 2.5;
    public static final int DEFAULT_STEPS = 28;
    public static final int MIN_STEPS = 4;
    public static final int MAX_STEPS = 50;
    public static final boolean DEFAULT_GO_FAST = false;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_OUTPUT_QUALITY = 80;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_KONTEXT_DEV;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", asOneOf(submittedFields.get("aspect_ratio"), ASPECT_RATIOS));
        Integer steps = asInteger(submittedFields.get("num_inference_steps"));
        if (steps != null) {
            params.put("num_inference_steps", Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps)));
        }
        Double guidance = asDouble(submittedFields.get("guidance"));
        if (guidance != null) {
            params.put("guidance", Math.max(0, Math.min(10, guidance)));
        }
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        params.put("go_fast", submittedFields.containsKey("go_fast"));
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
        defaults.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
        defaults.put("num_inference_steps", DEFAULT_STEPS);
        defaults.put("guidance", DEFAULT_GUIDANCE);
        defaults.put("go_fast", DEFAULT_GO_FAST);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        defaults.put("output_quality", DEFAULT_OUTPUT_QUALITY);
        // "seed" intenzionalmente assente: casuale di default.
        return defaults;
    }

    @Override
    public String fragmentName() {
        return "fragments/generation-params-flux-kontext-dev :: fields";
    }
}
