package org.dual.hexa.app.generation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dual.hexa.app.generation.domain.AppSecretType;
import org.dual.hexa.app.generation.domain.LoraException;
import org.dual.hexa.app.generation.domain.LoraPreset;
import org.dual.hexa.app.generation.domain.ReplicateModel;
import org.dual.hexa.app.generation.port.in.IModelCatalog;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.remote.RemoteServiceException;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.app.generation.port.out.ILoraPresetStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CRUD dei LoRA anagrafati. Sono solo preset di compilazione per le form di flux-dev-lora (sorgente + intensita' + trigger
 * words): la form invia comunque testo e scala, quindi cancellare o modificare un preset non tocca le generazioni passate.
 * Una sorgente {@code owner/nome} e' implicitamente un modello LoRA su Replicate: alla creazione/modifica del preset diventa anche
 * un modello del catalogo ({@link IModelCatalog#registerLoraFinetune}), disponibile ovunque lo e' ogni fine-tune. Cancellare il
 * preset NON toglie il modello dal catalogo (le generazioni passate lo referenziano).
 */
@Service
public class LoraPresetService implements ILoraPresets {

    private final ILoraPresetStore repository;
    private final Messages messages;
    private final IModelCatalog modelCatalog;
    private final ISystemEvents systemEvents;
    private final ISecrets secrets;
    private final Clock clock;

    @Autowired
    public LoraPresetService(ILoraPresetStore repository, Messages messages, IModelCatalog modelCatalog, ISystemEvents systemEvents,
                             ISecrets secrets) {
        this(repository, messages, modelCatalog, systemEvents, secrets, Clock.systemDefaultZone());
    }

    LoraPresetService(ILoraPresetStore repository, Messages messages, IModelCatalog modelCatalog, ISystemEvents systemEvents, ISecrets secrets, Clock clock) {
        this.repository = repository;
        this.messages = messages;
        this.modelCatalog = modelCatalog;
        this.systemEvents = systemEvents;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public List<LoraView> list() {
        Map<Long, ISecrets.SecretView> byId = secrets.list().stream().collect(Collectors.toMap(ISecrets.SecretView::id, Function.identity()));
        return repository.findAllOrderedByName().stream().map(p -> view(p, byId.get(p.getDefaultTokenId()))).toList();
    }

    /** Attributi di Model per le select di preset delle form di generazione ({@code loraPresets}): solo dove serve. */
    @Override
    public Map<String, List<LoraView>> formOptions() {
        return Map.of("loraPresets", list());
    }

    @Override
    public LoraView get(Long id) {
        LoraPreset preset = find(id);
        return view(preset, tokenOf(preset));
    }

    @Override
    public LoraView create(String name, String source, Double scale, String triggerWords, String note, Long defaultSecretId) {
        String cleanName = validName(name);
        if (repository.existsByNameIgnoreCase(cleanName)) {
            throw new LoraException(messages.get("loras.error.nameDuplicate", cleanName));
        }
        LoraPreset saved = repository.save(new LoraPreset(cleanName, validSource(source), validScale(scale),
                optional(triggerWords, "loras.error.triggerWordsTooLong"), optional(note, "loras.error.noteTooLong"), validToken(defaultSecretId), clock.instant()));
        registerAsModel(saved);
        return view(saved, tokenOf(saved));
    }

    @Override
    public LoraView update(Long id, String name, String source, Double scale, String triggerWords, String note, Long defaultSecretId) {
        LoraPreset existing = find(id);
        String cleanName = validName(name);
        if (repository.existsByNameIgnoreCaseAndIdNot(cleanName, id)) {
            throw new LoraException(messages.get("loras.error.nameDuplicate", cleanName));
        }
        Instant now = clock.instant();
        existing.update(cleanName, validSource(source), validScale(scale), optional(triggerWords, "loras.error.triggerWordsTooLong"),
                optional(note, "loras.error.noteTooLong"), validToken(defaultSecretId), now);
        LoraPreset saved = repository.save(existing);
        registerAsModel(saved);
        return view(saved, tokenOf(saved));
    }

    /**
     * La sorgente {@code owner/nome} e' un modello Replicate: lo censisce nel catalogo. Il preset e' gia' salvato e non dipende da
     * questo passo: un rifiuto (modello inesistente o non LoRA: la sorgente puo' essere altro) e' normale e silenzioso, un guasto del
     * servizio e' un evento di sistema; in entrambi i casi il preset resta.
     */
    private void registerAsModel(LoraPreset preset) {
        ReplicateModel.identifierOfSource(preset.getSource()).ifPresent(identifier -> {
            try {
                modelCatalog.registerLoraFinetune(identifier, preset.getName());
            } catch (RemoteServiceException e) {
                if (e.isReportable()) {
                    systemEvents.record("registerLoraModel", e);
                }
            }
        });
    }

    @Override
    public void delete(Long id) {
        repository.delete(find(id));
    }

    /** Mai il segreto: della vista fanno parte solo id, provider, nome e suffisso del token ({@code hint}). */
    private static LoraView view(LoraPreset p, ISecrets.SecretView token) {
        return new LoraView(p.getId(), p.getName(), p.getSource(), p.getScale(), p.getTriggerWords(), p.getNote(), p.getDefaultTokenId(),
                token == null ? null : token.type(), token == null ? null : token.name(), token == null ? null : token.hint());
    }

    /** Il token di default, se c'e' ancora (la FK lo scollega alla cancellazione, ma una lettura concorrente puo' non trovarlo). */
    private ISecrets.SecretView tokenOf(LoraPreset p) {
        if (p.getDefaultTokenId() == null) {
            return null;
        }
        try {
            return secrets.get(p.getDefaultTokenId());
        } catch (RemoteServiceException e) {
            return null;
        }
    }

    /** {@code null} = nessun token; altrimenti deve esistere ed essere un token HuggingFace o CivitAI. */
    private Long validToken(Long id) {
        if (id == null) {
            return null;
        }
        ISecrets.SecretView token;
        try {
            token = secrets.get(id);
        } catch (RemoteServiceException e) {
            throw new LoraException(messages.get("loras.error.tokenInvalid"));
        }
        boolean supported = java.util.Arrays.stream(AppSecretType.values()).anyMatch(p -> p.name().equals(token.type()));
        if (!supported) {
            throw new LoraException(messages.get("loras.error.tokenInvalid"));
        }
        return id;
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
