package org.hexa.app.generation.application.form;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.hexa.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

import static org.hexa.app.shared.domain.FormFields.asDouble;
import static org.hexa.app.shared.domain.FormFields.asInteger;
import static org.hexa.app.shared.domain.FormFields.asLong;
import static org.hexa.app.shared.domain.FormFields.asOneOf;
import static org.hexa.app.shared.domain.FormFields.isChecked;
import static org.hexa.app.shared.domain.FormFields.putIfPresent;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_FILL_PRO}: i campi di input di black-forest-labs/flux-fill-pro (inpainting; schema letto
 * da Replicate il 2026-10-02). Come {@link FluxFillDevParameterHandler} {@code image} e {@code mask} le aggiunge {@code GenerationService};
 * a differenza del dev NON ha LoRA, {@code num_outputs} (una prediction = un'immagine) ne' {@code disable_safety_checker}: ha
 * {@code safety_tolerance}, che pero' NON e' un campo del form: la forza {@code GenerationService} al massimo (6), come per
 * {@code disable_safety_checker} negli altri modelli ({@link GenerationFormType#safetyToleranceParam()}). {@code outpaint} non e' esposto: con
 * quell'opzione la maschera sarebbe ignorata.
 * <p>
 * {@code prompt_upsampling} e' una checkbox: va letta con {@code containsKey}, vedi {@link FluxKontextDevParameterHandler}.
 */
@Component
public class FluxFillProParameterHandler implements IGenerationParameterHandler {

    public static final Set<String> OUTPUT_FORMATS = Set.of("jpg", "png");

    public static final int DEFAULT_STEPS = 50;
    public static final int MIN_STEPS = 15;
    public static final int MAX_STEPS = 50;
    public static final double DEFAULT_GUIDANCE = 60;
    public static final double MIN_GUIDANCE = 1.5;
    public static final double MAX_GUIDANCE = 100;
    public static final boolean DEFAULT_PROMPT_UPSAMPLING = false;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_FILL_PRO;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        Integer steps = asInteger(submittedFields.get("steps"));
        if (steps != null) {
            params.put("steps", Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps)));
        }
        Double guidance = asDouble(submittedFields.get("guidance"));
        if (guidance != null) {
            params.put("guidance", Math.max(MIN_GUIDANCE, Math.min(MAX_GUIDANCE, guidance)));
        }
        params.put("prompt_upsampling", isChecked(submittedFields, "prompt_upsampling"));
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        putIfPresent(params, "output_format", asOneOf(submittedFields.get("output_format"), OUTPUT_FORMATS));
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("steps", DEFAULT_STEPS);
        defaults.put("guidance", DEFAULT_GUIDANCE);
        defaults.put("prompt_upsampling", DEFAULT_PROMPT_UPSAMPLING);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        // "seed" intenzionalmente assente: casuale di default.
        return defaults;
    }
}
