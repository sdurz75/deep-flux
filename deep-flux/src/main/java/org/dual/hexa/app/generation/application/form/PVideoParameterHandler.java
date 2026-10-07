package org.dual.hexa.app.generation.application.form;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.springframework.stereotype.Component;

import static org.dual.hexa.app.shared.domain.FormFields.asInteger;
import static org.dual.hexa.app.shared.domain.FormFields.asLong;
import static org.dual.hexa.app.shared.domain.FormFields.asOneOf;
import static org.dual.hexa.app.shared.domain.FormFields.isChecked;
import static org.dual.hexa.app.shared.domain.FormFields.putIfPresent;

/**
 * Handler del form-type {@link GenerationFormType#P_VIDEO}: i campi di
 * input di prunaai/p-video (schema letto da GET /v1/models/prunaai/p-video,
 * vedi migrazione V12). {@code image} non e' un campo di questo form: lo
 * aggiunge GenerationController quando il video nasce da una generazione
 * esistente ("Anima"), come data-URI del file salvato. {@code audio},
 * {@code last_frame_image}, {@code no_op}, {@code save_audio} e
 * {@code disable_safety_filter} (gia' true di default) non sono esposti.
 *
 * {@code draft} e {@code prompt_upsampling} sono checkbox: una checkbox
 * HTML deselezionata non sottomette la propria chiave, quindi vanno lette
 * con {@code containsKey} (vedi Flux2Klein9bParameterHandler). {@link #DEFAULT_PROMPT_UPSAMPLING} e' {@code false}
 * (scelta dell'utente, diverso dal default Replicate).
 */
@Component
public class PVideoParameterHandler implements IGenerationParameterHandler {

    public static final Set<String> ASPECT_RATIOS = Set.of("16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "1:1");
    public static final Set<String> RESOLUTIONS = Set.of("720p", "1080p");
    public static final Set<Integer> FPS_VALUES = Set.of(24, 48);
    public static final int MIN_DURATION = 1;
    public static final int MAX_DURATION = 20;

    public static final int DEFAULT_DURATION = 5;
    public static final String DEFAULT_ASPECT_RATIO = "16:9";
    public static final String DEFAULT_RESOLUTION = "720p";
    public static final int DEFAULT_FPS = 24;
    public static final boolean DEFAULT_DRAFT = false;
    public static final boolean DEFAULT_PROMPT_UPSAMPLING = false;

    @Override
    public GenerationFormType formType() {
        return GenerationFormType.P_VIDEO;
    }

    @Override
    public Map<String, Object> toParameterMap(Map<String, String> submittedFields) {
        Map<String, Object> params = new LinkedHashMap<>();
        Integer duration = asInteger(submittedFields.get("duration"));
        if (duration != null) {
            params.put("duration", Math.max(MIN_DURATION, Math.min(MAX_DURATION, duration)));
        }
        putIfPresent(params, "aspect_ratio", asOneOf(submittedFields.get("aspect_ratio"), ASPECT_RATIOS));
        putIfPresent(params, "resolution", asOneOf(submittedFields.get("resolution"), RESOLUTIONS));
        Integer fps = asInteger(submittedFields.get("fps"));
        putIfPresent(params, "fps", fps != null && FPS_VALUES.contains(fps) ? fps : null);
        params.put("draft", isChecked(submittedFields, "draft"));
        params.put("prompt_upsampling", isChecked(submittedFields, "prompt_upsampling"));
        putIfPresent(params, "seed", asLong(submittedFields.get("seed")));
        return params;
    }

    @Override
    public Map<String, Object> defaultFields() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("duration", DEFAULT_DURATION);
        defaults.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
        defaults.put("resolution", DEFAULT_RESOLUTION);
        defaults.put("fps", DEFAULT_FPS);
        defaults.put("draft", DEFAULT_DRAFT);
        defaults.put("prompt_upsampling", DEFAULT_PROMPT_UPSAMPLING);
        // "seed" intenzionalmente assente: casuale di default, come gli altri form-type.
        return defaults;
    }
}
