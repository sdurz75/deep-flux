package org.dual.hexa.app.generation.application.form;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

import static org.dual.hexa.app.shared.domain.FormFields.asDouble;
import static org.dual.hexa.app.shared.domain.FormFields.asInteger;
import static org.dual.hexa.app.shared.domain.FormFields.asLong;
import static org.dual.hexa.app.shared.domain.FormFields.asOneOf;
import static org.dual.hexa.app.shared.domain.FormFields.asText;
import static org.dual.hexa.app.shared.domain.FormFields.putIfPresent;

/**
 * Handler del form-type {@link GenerationFormType#FLUX_LORA_FINETUNE}: un LoRA di Flux addestrato su Replicate (es. flux-lora-ff3).
 * Tutti i modelli di questo tipo hanno lo stesso schema di input e funzionano allo stesso modo: un altro fine-tune si censisce con un
 * INSERT in {@code replicate_model} (versione pinnata + questo form-type), senza codice. In piu' dei campi base, {@code extra_lora}/
 * {@code extra_lora_scale} (seconda LoRA, solo sorgenti pubbliche: lo schema non ha token). I campi sono gli
 * stessi 9 campi tipizzati della vecchia form "generica" condivisa da
 * tutti i modelli (aspect_ratio fisso a "custom", width, height,
 * output_format, num_inference_steps, guidance_scale, seed, lora_scale,
 * flux_model, num_outputs), solo spostati qui e riletti da una
 * {@code Map<String,String>} di campi sottomessi invece che da parametri
 * Java tipizzati — nessun campo e' cambiato, e' un rename/reshape, non
 * una riprogettazione (vedi il piano di questa feature). In piu' {@code prompt_strength}:
 * il modello accetta anche {@code image} (img2img) e {@code mask} (inpainting), opzionali
 * (schema della versione pinnata letto da Replicate il 2026-10-03); con un'immagine
 * {@code aspect_ratio}/{@code width}/{@code height} sono ignorati dal modello.
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
public class FluxLoraFinetuneParameterHandler implements IGenerationParameterHandler {

    public static final int DEFAULT_WIDTH = 848;
    public static final int DEFAULT_HEIGHT = 1280;
    public static final String DEFAULT_OUTPUT_FORMAT = "jpg";
    public static final int DEFAULT_NUM_INFERENCE_STEPS = 28;
    public static final double DEFAULT_LORA_SCALE = 1;
    public static final double DEFAULT_EXTRA_LORA_SCALE = 1;
    public static final double DEFAULT_GUIDANCE_SCALE = 3;
    public static final String DEFAULT_FLUX_MODEL = "dev";
    public static final int DEFAULT_NUM_OUTPUTS = 1;
    public static final double DEFAULT_PROMPT_STRENGTH = 0.8;
    /**
     * Enum di {@code megapixels} dello schema Replicate (letto il 2026-10-03). Conta solo con un'immagine di partenza: senza, la
     * dimensione la danno {@code width}/{@code height} (con {@code aspect_ratio=custom}); con l'immagine il modello ignora quelle e usa questo.
     */
    public static final Set<String> MEGAPIXELS = Set.of("1", "0.25");
    public static final String DEFAULT_MEGAPIXELS = "1";

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.FLUX_LORA_FINETUNE;
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
        // Seconda LoRA dello schema (Replicate, HuggingFace, CivitAI o URL; vuoto = nessuna). Lo schema non ha token: solo sorgenti pubbliche.
        putIfPresent(params, "extra_lora", asText(submittedFields.get("extra_lora")));
        putIfPresent(params, "extra_lora_scale", asDouble(submittedFields.get("extra_lora_scale")));
        putIfPresent(params, "model", submittedFields.get("flux_model"));
        putIfPresent(params, "num_outputs", asNumOutputs(submittedFields.get("num_outputs")));
        // Ha effetto solo con un'immagine di partenza (img2img/inpainting: l'"image" e la "mask" le aggiunge GenerationService).
        putIfPresent(params, "prompt_strength", asDouble(submittedFields.get("prompt_strength")));
        putIfPresent(params, "megapixels", asOneOf(submittedFields.get("megapixels"), MEGAPIXELS));
        return params;
    }

    /** Il checkpoint base e' salvato sotto la chiave {@code model} del provider, ma nel form il campo e' {@code flux_model}. */
    @Override
    public Map<String, String> toFormFields(Map<String, Object> parameters) {
        Map<String, String> fields = IGenerationParameterHandler.super.toFormFields(parameters);
        Object fluxModel = parameters.get("model");
        if (fluxModel != null) {
            fields.put("flux_model", String.valueOf(fluxModel));
        }
        return fields;
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
        defaults.put("lora_scale", DEFAULT_LORA_SCALE);
        // Vuoto = nessuna LoRA extra; sta nei default perche' populateFormTypeFields / toFormFields conservano solo queste chiavi.
        defaults.put("extra_lora", "");
        defaults.put("extra_lora_scale", DEFAULT_EXTRA_LORA_SCALE);
        defaults.put("guidance_scale", DEFAULT_GUIDANCE_SCALE);
        defaults.put("prompt_strength", DEFAULT_PROMPT_STRENGTH);
        defaults.put("megapixels", DEFAULT_MEGAPIXELS);
        // "seed" intenzionalmente assente: il default e' quello di Replicate
        // stesso (casuale, placeholder "casuale" nel fragment).
        return defaults;
    }
}
