package org.dual.replicate.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.replicate.domain.GenerationFormType;
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
 * {@link GenerationService#create}, per ogni modello.
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
public class Flux2Klein9bParameterHandler implements GenerationParameterHandler {

    public static final String DEFAULT_ASPECT_RATIO = "1:1";
    public static final String DEFAULT_MEGAPIXELS = "1";
    public static final boolean DEFAULT_GO_FAST = false;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_2_KLEIN_9B;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfPresent(params, "aspect_ratio", submittedFields.get("aspect_ratio"));
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
        // "seed" e "output_quality" intenzionalmente assenti: il default
        // e' quello di Replicate stesso (applicato quando la chiave manca
        // del tutto dall'input, vedi toParameterMap/putIfPresent sopra),
        // non un valore che l'app forza in UI - stesso principio gia'
        // adottato per seed (placeholder "casuale" nel fragment).
        return defaults;
    }

    @Override
    public String fragmentName() {
        return "fragments/generation-params-flux-2-klein-9b :: fields";
    }
}
