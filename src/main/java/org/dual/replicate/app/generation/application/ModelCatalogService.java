package org.dual.replicate.app.generation.application;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.ReplicateModel;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.app.generation.port.out.IModelStore;
import org.springframework.stereotype.Component;

/**
 * Catalogo dei modelli Replicate proposti nel combobox di /generations/new
 * e /deep-chat: censiti a mano nel DB (tabella REPLICATE_MODEL, vedi
 * migrazione V6), non piu' fetchati dal vivo da Replicate a ogni avvio.
 * Tabella minuscola, nessuna cache: ogni chiamata interroga direttamente
 * {@link IModelStore}, cosi' una riga modificata via SQL e'
 * visibile subito, senza dover riavviare l'app.
 */
@Component
public class ModelCatalogService implements IModelCatalog {

    private final IModelStore repository;

    public ModelCatalogService(IModelStore repository) {
        this.repository = repository;
    }

    /** Modelli censiti attivi, ordinati per visualizzazione nel combobox. */
    @Override
    public List<ReplicateModel> models() {
        return repository.findActiveOrdered();
    }

    /**
     * Solo i modelli attivi del tipo di media dato, ESCLUSI quelli di modifica
     * (vedi {@link #editModels()}): /deep-chat e /generations/new propongono i
     * soli modelli text-to-image, che non richiedono un'immagine sorgente.
     */
    @Override
    public List<ReplicateModel> models(GenerationKind kind) {
        return models().stream().filter(m -> m.getFormType().kind() == kind && !m.getFormType().isEdit()).toList();
    }

    /** Modelli attivi di modifica immagine (sorgente obbligatoria), pagina /generations/new?kind=edit. */
    @Override
    public List<ReplicateModel> editModels() {
        return models().stream().filter(m -> m.getFormType().isEdit()).toList();
    }

    /** True se {@code id} e' un modello di modifica censito attivo. */
    @Override
    public boolean containsEdit(String id) {
        return formTypeOf(id).map(GenerationFormType::isEdit).orElse(false);
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
        return formTypeOf(id).map(t -> t.kind() == kind && !t.isEdit()).orElse(false);
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

    @Override
    public String idsAsCsv() {
        return models().stream().map(ReplicateModel::getIdentifier).collect(Collectors.joining(", "));
    }

    private Optional<ReplicateModel> find(String id) {
        return models().stream().filter(m -> m.getIdentifier().equals(id)).findFirst();
    }
}
