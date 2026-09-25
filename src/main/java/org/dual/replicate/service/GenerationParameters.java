package org.dual.replicate.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mapping condiviso, verso il vocabolario Replicate (snake_case), dei 10
 * campi tipizzati esposti da fragments/generation-params.html (usato sia
 * dal form diretto, GenerationController, sia dalla chat,
 * DeepChatApiController): passthrough opaco, nessuno di questi nomi e'
 * noto a GenerationService/ReplicateClient, che vedono solo la
 * Map risultante. Solo i valori non nulli finiscono nella mappa, cosi'
 * un modello che non supporta un parametro non lo riceve.
 *
 * {@code fluxModel} finisce nella mappa sotto la chiave "model": e' il
 * campo che i fine-tune LoRA di Flux (i "modelli personali" del
 * catalogo, vedi ReplicateModelCatalog) accettano nel loro input per
 * scegliere il checkpoint base ("dev" o "schnell") su cui girare
 * l'inferenza — non ha nulla a che vedere con l'"owner/name" del
 * modello Replicate scelto nel combobox (quello resta un parametro Java
 * separato, passato a parte a GenerationService.create). La stessa
 * chiave "model" per due concetti diversi e' solo una coincidenza del
 * vocabolario di Replicate, non un collegamento nel codice.
 */
public final class GenerationParameters {

    public static final int DEFAULT_WIDTH = 848;
    public static final int DEFAULT_HEIGHT = 1280;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_NUM_INFERENCE_STEPS = 28;
    public static final double DEFAULT_GUIDANCE_SCALE = 3;
    public static final double DEFAULT_LORA_SCALE = 1;
    public static final String DEFAULT_FLUX_MODEL = "dev";
    public static final int DEFAULT_NUM_OUTPUTS = 1;

    private GenerationParameters() {
    }

    public static Map<String, Object> toMap(String aspectRatio, Integer width, Integer height,
                                             String outputFormat, Integer numInferenceSteps,
                                             Double guidanceScale, Long seed, Double loraScale,
                                             String fluxModel, Integer numOutputs) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", aspectRatio);
        putIfPresent(params, "width", width);
        putIfPresent(params, "height", height);
        putIfPresent(params, "output_format", outputFormat);
        putIfPresent(params, "num_inference_steps", numInferenceSteps);
        putIfPresent(params, "guidance_scale", guidanceScale);
        putIfPresent(params, "seed", seed);
        putIfPresent(params, "lora_scale", loraScale);
        putIfPresent(params, "model", fluxModel);
        putIfPresent(params, "num_outputs", numOutputs);
        return params;
    }

    private static void putIfPresent(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }
}
