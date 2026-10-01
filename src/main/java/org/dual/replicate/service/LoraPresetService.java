package org.dual.replicate.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.dual.replicate.domain.LoraPreset;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.repository.LoraPresetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CRUD dei LoRA anagrafati. Sono solo preset di compilazione per le form di flux-dev-lora (sorgente + intensita' + trigger
 * words): la form invia comunque testo e scala, quindi cancellare o modificare un preset non tocca le generazioni passate.
 */
@Service
public class LoraPresetService {

    public static final int MAX_NAME = 60;
    public static final int MAX_SOURCE = 500;
    public static final int MAX_TEXT = 500;
    /** Stessi estremi del campo {@code lora_scale} della form. */
    public static final double MIN_SCALE = -1;
    public static final double MAX_SCALE = 3;
    public static final double DEFAULT_SCALE = 1;

    /** Vista per la UI e per le select delle form. */
    public record LoraView(Long id, String name, String source, double scale, String triggerWords, String note) {
    }

    private final LoraPresetRepository repository;
    private final Messages messages;
    private final Clock clock;

    @Autowired
    public LoraPresetService(LoraPresetRepository repository, Messages messages) {
        this(repository, messages, Clock.systemDefaultZone());
    }

    LoraPresetService(LoraPresetRepository repository, Messages messages, Clock clock) {
        this.repository = repository;
        this.messages = messages;
        this.clock = clock;
    }

    public List<LoraView> list() {
        return repository.findAllByOrderByNameAsc().stream().map(LoraPresetService::view).toList();
    }

    /** Attributi di Model per le select di preset delle form di generazione ({@code loraPresets}): solo dove serve. */
    public Map<String, List<LoraView>> formOptions() {
        return Map.of("loraPresets", list());
    }

    public LoraView get(Long id) {
        return view(find(id));
    }

    public LoraView create(String name, String source, Double scale, String triggerWords, String note) {
        String cleanName = validName(name);
        if (repository.existsByNameIgnoreCase(cleanName)) {
            throw new LoraException(messages.get("loras.error.nameDuplicate", cleanName));
        }
        LoraPreset saved = repository.save(new LoraPreset(cleanName, validSource(source), validScale(scale),
                optional(triggerWords, "loras.error.triggerWordsTooLong"), optional(note, "loras.error.noteTooLong"), clock.instant()));
        return view(saved);
    }

    public LoraView update(Long id, String name, String source, Double scale, String triggerWords, String note) {
        LoraPreset existing = find(id);
        String cleanName = validName(name);
        if (repository.existsByNameIgnoreCaseAndIdNot(cleanName, id)) {
            throw new LoraException(messages.get("loras.error.nameDuplicate", cleanName));
        }
        Instant now = clock.instant();
        existing.update(cleanName, validSource(source), validScale(scale), optional(triggerWords, "loras.error.triggerWordsTooLong"),
                optional(note, "loras.error.noteTooLong"), now);
        return view(repository.save(existing));
    }

    public void delete(Long id) {
        repository.delete(find(id));
    }

    private static LoraView view(LoraPreset p) {
        return new LoraView(p.getId(), p.getName(), p.getSource(), p.getScale(), p.getTriggerWords(), p.getNote());
    }

    private LoraPreset find(Long id) {
        return repository.findById(id).orElseThrow(() -> new LoraException(messages.get("loras.error.notFound")));
    }

    private String validName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new LoraException(messages.get("loras.error.nameRequired"));
        }
        if (clean.length() > MAX_NAME) {
            throw new LoraException(messages.get("loras.error.nameTooLong", MAX_NAME));
        }
        return clean;
    }

    private String validSource(String source) {
        String clean = source == null ? "" : source.strip();
        if (clean.isEmpty()) {
            throw new LoraException(messages.get("loras.error.sourceRequired"));
        }
        if (clean.length() > MAX_SOURCE) {
            throw new LoraException(messages.get("loras.error.sourceTooLong", MAX_SOURCE));
        }
        return clean;
    }

    /** {@code null} = intensita' predefinita (1). */
    private double validScale(Double scale) {
        if (scale == null) {
            return DEFAULT_SCALE;
        }
        if (scale.isNaN() || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new LoraException(messages.get("loras.error.scaleRange", MIN_SCALE, MAX_SCALE));
        }
        return scale;
    }

    /** Testo facoltativo: vuoto = null; oltre {@link #MAX_TEXT} e' un rifiuto con il messaggio {@code tooLongKey}. */
    private String optional(String value, String tooLongKey) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() > MAX_TEXT) {
            throw new LoraException(messages.get(tooLongKey, MAX_TEXT));
        }
        return clean;
    }
}
