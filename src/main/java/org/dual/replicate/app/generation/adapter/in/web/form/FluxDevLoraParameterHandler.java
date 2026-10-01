package org.dual.replicate.app.generation.adapter.in.web.form;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.app.generation.domain.ApiTokenProvider;
import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_DEV_LORA}: i campi di input di
 * black-forest-labs/flux-dev-lora (schema letto da Replicate il 2026-10-01). Come
 * {@link FluxKreaDevParameterHandler} (aspect_ratio/megapixels, go_fast, guidance...) piu':
 * <ul>
 *   <li>{@code lora_weights}/{@code extra_lora} (Replicate {@code owner/name[/version]}, URL HuggingFace/CivitAI o un
 *       .safetensors) con le rispettive scale; vuoti = nessun LoRA (FLUX dev puro);</li>
 *   <li>{@code prompt_strength}, che ha effetto solo con un'immagine di partenza (img2img: l'{@code image} la aggiunge
 *       {@code IGenerations#create} dal {@code sourceUpload}, come per p-video);</li>
 *   <li>{@code hf_token_id}/{@code civitai_token_id}: l'ID del token salvato (CRUD {@code /tokens}) scelto per nome nelle
 *       select, per i LoRA privati. Il token in chiaro non attraversa mai form/chat: {@code GenerationService#doCreate}
 *       sostituisce l'ID con {@code hf_api_token}/{@code civitai_api_token} solo nell'input per Replicate
 *       ({@code TokenInputResolver#resolveInto}); nel PARAMETERS_JSON salvato resta l'ID.</li>
 * </ul>
 * {@code aspect_ratio} non ha {@code match_input_image}: con un'immagine il modello usa comunque quella dell'immagine.
 * {@code disable_safety_checker} non e' esposto: lo forza {@code IGenerations#create} per ogni immagine.
 * {@code go_fast} e' deliberatamente {@code false} per default (non il default Replicate), come krea-dev/klein-9b;
 * essendo una checkbox va letta con {@code containsKey}.
 */
@Component
public class FluxDevLoraParameterHandler implements IGenerationParameterHandler {

    public static final Set<String> ASPECT_RATIOS = Set.of("1:1", "16:9", "21:9", "3:2", "2:3", "4:5", "5:4", "3:4",
            "4:3", "9:16", "9:21");
    public static final Set<String> MEGAPIXELS = Set.of("1", "0.25");
    public static final String DEFAULT_ASPECT_RATIO = "1:1";
    public static final String DEFAULT_MEGAPIXELS = "1";
    public static final boolean DEFAULT_GO_FAST = false;
    public static final int DEFAULT_NUM_OUTPUTS = 1;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_NUM_INFERENCE_STEPS = 28;
    public static final int DEFAULT_OUTPUT_QUALITY = 80;
    public static final double DEFAULT_GUIDANCE = 3;
    public static final double DEFAULT_LORA_SCALE = 1;
    public static final double DEFAULT_EXTRA_LORA_SCALE = 1;
    public static final double DEFAULT_PROMPT_STRENGTH = 0.8;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_DEV_LORA;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "lora_weights", asText(submittedFields.get("lora_weights")));
        putIfPresent(params, "lora_scale", asDouble(submittedFields.get("lora_scale")));
        putIfPresent(params, "extra_lora", asText(submittedFields.get("extra_lora")));
        putIfPresent(params, "extra_lora_scale", asDouble(submittedFields.get("extra_lora_scale")));
        putIfPresent(params, ApiTokenProvider.HUGGINGFACE.idParam(), asLong(submittedFields.get(ApiTokenProvider.HUGGINGFACE.idParam())));
        putIfPresent(params, ApiTokenProvider.CIVITAI.idParam(), asLong(submittedFields.get(ApiTokenProvider.CIVITAI.idParam())));
        putIfPresent(params, "aspect_ratio", asOneOf(submittedFields.get("aspect_ratio"), ASPECT_RATIOS));
        putIfPresent(params, "megapixels", asOneOf(submittedFields.get("megapixels"), MEGAPIXELS));
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        params.put("go_fast", submittedFields.containsKey("go_fast"));
        putIfPresent(params, "guidance", asDouble(submittedFields.get("guidance")));
        putIfPresent(params, "prompt_strength", asDouble(submittedFields.get("prompt_strength")));
        putIfPresent(params, "num_outputs", asInteger(submittedFields.get("num_outputs")));
        putIfPresent(params, "output_format", submittedFields.get("output_format"));
        putIfPresent(params, "output_quality", asInteger(submittedFields.get("output_quality")));
        putIfPresent(params, "num_inference_steps", asInteger(submittedFields.get("num_inference_steps")));
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("lora_scale", DEFAULT_LORA_SCALE);
        defaults.put("extra_lora_scale", DEFAULT_EXTRA_LORA_SCALE);
        defaults.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
        defaults.put("megapixels", DEFAULT_MEGAPIXELS);
        defaults.put("go_fast", DEFAULT_GO_FAST);
        defaults.put("num_outputs", DEFAULT_NUM_OUTPUTS);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        defaults.put("num_inference_steps", DEFAULT_NUM_INFERENCE_STEPS);
        defaults.put("output_quality", DEFAULT_OUTPUT_QUALITY);
        defaults.put("guidance", DEFAULT_GUIDANCE);
        defaults.put("prompt_strength", DEFAULT_PROMPT_STRENGTH);
        // Nessun token scelto di default ("": opzione vuota della select); serve qui perche' populateFormTypeFields conserva
        // solo le chiavi dei default (la scelta sopravvive a un errore di validazione o a un cambio modello).
        defaults.put(ApiTokenProvider.HUGGINGFACE.idParam(), "");
        defaults.put(ApiTokenProvider.CIVITAI.idParam(), "");
        // "seed" intenzionalmente assente (casuale, come gli altri form-type); "lora_weights"/"extra_lora" vuoti di default.
        return defaults;
    }

    @Override
    public String fragmentName() {
        return "fragments/app/generation-params-flux-dev-lora :: fields";
    }

    /** Testo ripulito, {@code null} se assente o vuoto (la chiave viene omessa e vale il default di Replicate). */
    private String asText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
