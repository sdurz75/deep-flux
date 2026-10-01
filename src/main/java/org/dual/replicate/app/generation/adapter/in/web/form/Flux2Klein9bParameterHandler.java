package org.dual.replicate.app.generation.adapter.in.web.form;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_2_KLEIN_9B}: i
 * campi di input di black-forest-labs/flux-2-klein-9b (schema letto da
 * Replicate, vedi migrazione V7), un modello diverso da FLUX_LORA_FF3
 * (nessun width/height/steps/guidance/lora_scale/num_outputs) — niente
 * di condiviso con {@link FluxLoraFf3ParameterHandler} a parte lo
 * scheletro dell'interfaccia. {@code images} (input per image-to-image)
 * non e' esposto in UI. {@code disable_safety_checker} nemmeno: non e'
 * un campo di questo handler, e' forzato a true incondizionatamente da
 * {@code IGenerations#create}, per ogni modello immagine.
 *
 * {@code go_fast} e' l'unico campo booleano/checkbox del progetto: una
 * checkbox HTML non sottomette affatto la propria chiave quando e'
 * deselezionata, quindi va letta con {@code containsKey}, non
 * {@code get(...)} — altrimenti "deselezionata" e "non ancora inviata"
 * sarebbero indistinguibili. {@link #DEFAULT_GO_FAST} e' deliberatamente
 * {@code false}, non il default Replicate ({@code true}): scelta
 * esplicita dell'utente, non allineata al modello.
 */
@Component
public class Flux2Klein9bParameterHandler implements IGenerationParameterHandler {

    public static final java.util.Set<String> ASPECT_RATIOS = java.util.Set.of("1:1", "16:9", "9:16", "3:2", "2:3",
            "4:3", "3:4", "5:4", "4:5", "21:9", "9:21", "match_input_image");
    public static final String DEFAULT_ASPECT_RATIO = "1:1";
    public static final String DEFAULT_MEGAPIXELS = "1";
    public static final boolean DEFAULT_GO_FAST = false;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_OUTPUT_QUALITY = 80;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_2_KLEIN_9B;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", asOneOf(submittedFields.get("aspect_ratio"), ASPECT_RATIOS));
        putIfPresent(params, "megapixels", submittedFields.get("megapixels"));
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        params.put("go_fast", submittedFields.containsKey("go_fast"));
        putIfPresent(params, "output_format", submittedFields.get("output_format"));
        putIfPresent(params, "output_quality", asInteger(submittedFields.get("output_quality")));
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
        defaults.put("megapixels", DEFAULT_MEGAPIXELS);
        defaults.put("go_fast", DEFAULT_GO_FAST);
        defaults.put("output_format", DEFAULT_OUTPUT_FORMAT);
        defaults.put("output_quality", DEFAULT_OUTPUT_QUALITY);
        // "seed" intenzionalmente assente: il default e' quello di Replicate
        // stesso (casuale, placeholder "casuale" nel fragment).
        return defaults;
    }

    @Override
    public String fragmentName() {
        return "fragments/app/generation-params-flux-2-klein-9b :: fields";
    }
}
