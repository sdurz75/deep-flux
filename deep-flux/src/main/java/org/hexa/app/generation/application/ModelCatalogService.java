package org.hexa.app.generation.application;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import java.time.Clock;

import org.hexa.app.generation.domain.GenerationFormType;
import org.hexa.app.generation.domain.GenerationKind;
import org.hexa.app.generation.domain.ModelVersion;
import org.hexa.app.generation.domain.ReplicateException;
import org.hexa.app.generation.domain.ReplicateModel;
import org.hexa.app.generation.port.in.IModelCatalog;
import org.hexa.app.generation.port.out.IModelStore;
import org.hexa.app.generation.port.out.IPredictionGateway;
import org.hexa.core.kernel.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Catalogo dei modelli Replicate proposti nel combobox di /generations/new
 * e /deep-chat: censiti a mano nel DB (tabella REPLICATE_MODEL, vedi
 * migrazione V6), non piu' fetchati dal vivo da Replicate a ogni avvio.
 * Le righe le aggiunge a runtime solo {@link #registerLoraFinetune}. Tabella minuscola, nessuna cache: ogni chiamata interroga direttamente
 * {@link IModelStore}, cosi' una riga modificata via SQL e'
 * visibile subito, senza dover riavviare l'app.
 */
@Component
public class ModelCatalogService implements IModelCatalog {

    private final IModelStore repository;
    private final IPredictionGateway gateway;
    private final Messages messages;
    private final Clock clock;

    @Autowired
    public ModelCatalogService(IModelStore repository, IPredictionGateway gateway, Messages messages) {
        this(repository, gateway, messages, Clock.systemDefaultZone());
    }

    ModelCatalogService(IModelStore repository, IPredictionGateway gateway, Messages messages, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.messages = messages;
        this.clock = clock;
    }

    /** Modelli censiti attivi, ordinati per visualizzazione nel combobox. */
    @Override
    public List<ReplicateModel> models() {
        return repository.findActiveOrdered();
    }

    /**
     * Solo i modelli attivi del tipo di media dato che funzionano senza un'immagine
     * sorgente ({@link GenerationFormType#sourceRequired()} falso): /deep-chat e il default
     * di /generations/new non possono proporre kontext o flux-fill-*.
     */
    @Override
    public List<ReplicateModel> models(GenerationKind kind) {
        return formModels(kind).stream().filter(m -> !m.getFormType().sourceRequired()).toList();
    }

    @Override
    public List<ReplicateModel> formModels(GenerationKind kind) {
        return models().stream().filter(m -> m.getFormType().kind() == kind).toList();
    }

    /** Primo modello attivo del catalogo, se ce n'e' uno: preselezionato in /generations/new e /deep-chat. */
    @Override
    public Optional<ReplicateModel> defaultModel() {
        List<ReplicateModel> models = models(GenerationKind.IMAGE);
        return models.isEmpty() ? Optional.empty() : Optional.of(models.get(0));
    }

    /** True se {@code id} e' un modello censito attivo che produce media del tipo dato. */
    @Override
    public boolean contains(String id, GenerationKind kind) {
        return formTypeOf(id).map(t -> t.kind() == kind && !t.sourceRequired()).orElse(false);
    }

    @Override
    public boolean contains(String id) {
        return find(id).isPresent();
    }

    /**
     * Version censita per {@code id} ("owner/name"), se nota: va sempre
     * passata esplicitamente a ReplicateClient.createPrediction invece di
     * lasciare che usi lo shortcut "/models/{owner}/{name}/predictions"
     * (ultima versione implicita) — non tutti i modelli lo supportano
     * (verificato dal vivo: 404 sui LoRA personali account sdurz75, pur
     * esistendo ed essendo pubblici). A differenza della vecchia
     * ModelCatalogService, questo valore non e' piu' risolto dal vivo
     * contro "l'ultima versione pubblicata" ma censito nella riga DB: se
     * il modello viene ripubblicato, l'hash va aggiornato con una nuova
     * migrazione.
     */
    @Override
    public Optional<String> versionOf(String id) {
        return find(id).map(ReplicateModel::getVersion);
    }

    @Override
    public Optional<GenerationFormType> formTypeOf(String id) {
        return find(id).map(ReplicateModel::getFormType);
    }

    /** Campo che distingue un LoRA di Flux addestrato (stesso schema di {@code FLUX_LORA_FINETUNE}) da un modello qualunque. */
    static final String LORA_FIELD = "lora_scale";

    @Override
    public Optional<ReplicateModel> registerLoraFinetune(String identifier, String description) {
        String[] ownerAndName = identifier.split("/", 2);
        if (ownerAndName.length != 2 || repository.exists(ownerAndName[0], ownerAndName[1])) {
            return Optional.empty();
        }
        ModelVersion version = gateway.latestVersion(identifier)
                .orElseThrow(() -> new ReplicateException(messages.get("replicate.error.modelNotFound", identifier)));
        // Schema non noto (set vuoto) = si accetta; noto ma senza lora_scale = non e' un LoRA di Flux: mai censirlo con la form sbagliata.
        if (!version.inputFields().isEmpty() && !version.inputFields().contains(LORA_FIELD)) {
            throw new ReplicateException(messages.get("replicate.error.modelNotLora", identifier));
        }
        return Optional.of(repository.save(new ReplicateModel(ownerAndName[0], ownerAndName[1], version.id(), description,
                GenerationFormType.FLUX_LORA_FINETUNE, repository.nextSortOrder(), clock.instant())));
    }

    @Override
    public String idsAsCsv() {
        return models().stream().map(ReplicateModel::getIdentifier).collect(Collectors.joining(", "));
    }

    private Optional<ReplicateModel> find(String id) {
        return models().stream().filter(m -> m.getIdentifier().equals(id)).findFirst();
    }
}
