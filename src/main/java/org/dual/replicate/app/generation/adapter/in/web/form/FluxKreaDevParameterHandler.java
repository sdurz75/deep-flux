package org.dual.replicate.app.generation.adapter.in.web.form;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_KREA_DEV}: i
 * campi di input di black-forest-labs/flux-krea-dev (schema letto da
 * Replicate) — piu' vicino a {@link Flux2Klein9bParameterHandler} che a
 * {@link FluxLoraFf3ParameterHandler} (aspect_ratio/megapixels invece di
 * width/height custom, niente lora_scale/variante dev-schnell), ma con
 * 3 campi in piu' che klein-9b non accetta (guidance, num_outputs,
 * num_inference_steps) e un megapixels a sole due opzioni (0.25/1,
 * contro le 5 di klein-9b) — condividere il form-type di klein-9b
 * esporrebbe controlli senza effetto su quel modello, da cui un terzo
 * form-type dedicato invece di generalizzare quello esistente (vedi il
 * piano di questa feature). {@code image} (input per image-to-image) e
 * {@code disable_safety_checker} non sono esposti in UI: il primo per
 * la stessa ragione degli altri due form-type (nessuna UI di upload in
 * questo progetto), il secondo perche' forzato a true incondizionatamente
 * da {@code IGenerations#create}, per ogni modello.
 *
 * {@code go_fast} e' l'unico altro campo booleano/checkbox del progetto
 * insieme a quello di klein-9b: una checkbox HTML non sottomette affatto
 * la propria chiave quando e' deselezionata, quindi va letta con
 * {@code containsKey}, non {@code get(...)}. {@link #DEFAULT_GO_FAST} e'
 * deliberatamente {@code false}, non il default Replicate ({@code true}):
 * stessa scelta esplicita di klein-9b, determinismo con un seed esplicito
 * non allineato al default del modello.
 */
@Component
public class FluxKreaDevParameterHandler implements IGenerationParameterHandler {

    public static final java.util.Set<String> ASPECT_RATIOS = java.util.Set.of("1:1", "16:9", "9:16", "3:2", "2:3",
            "4:3", "3:4", "5:4", "4:5", "21:9", "9:21", "match_input_image");
    public static final String DEFAULT_ASPECT_RATIO = "1:1";
    public static final String DEFAULT_MEGAPIXELS = "1";
    public static final boolean DEFAULT_GO_FAST = false;
    public static final int DEFAULT_NUM_OUTPUTS = 1;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_NUM_INFERENCE_STEPS = 28;
    public static final int DEFAULT_OUTPUT_QUALITY = 80;
    public static final double DEFAULT_GUIDANCE = 3;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_KREA_DEV;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", asOneOf(submittedFields.get("aspect_ratio"), ASPECT_RATIOS));
        putIfPresent(params, "megapixels", submittedFields.get("megapixels"));
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        params.put("go_fast", submittedFields.containsKey("go_fast"));
        putIfPresent(params, "guidance", asDouble(submittedFields.get("guidance")));
        putIfPresent(params, "num_outputs", asNumOutputs(submittedFields.get("num_outputs")));
        putIfPresent(params, "output_format", submittedFields.get("output_format"));
        putIfPresent(params, "output_quality", asInteger(submittedFields.get("output_quality")));
        putIfPresent(params, "num_inference_steps", asInteger(submittedFields.get("num_inference_steps")));
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
        defaults.put("megapixels", DEFAULT_MEGAPIXELS);
        defaults.put("go_fast", DEFAULT_GO_FAST);
        defaults.put("num_outputs", DEFAULT_NUM_OUTPUTS);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        defaults.put("num_inference_steps", DEFAULT_NUM_INFERENCE_STEPS);
        defaults.put("output_quality", DEFAULT_OUTPUT_QUALITY);
        defaults.put("guidance", DEFAULT_GUIDANCE);
        // "seed" intenzionalmente assente: il default e' quello di Replicate
        // stesso (casuale, placeholder "casuale" nel fragment).
        return defaults;
    }

    @Override
    public String fragmentName() {
        return "fragments/app/generation-params-flux-krea-dev :: fields";
    }
}
