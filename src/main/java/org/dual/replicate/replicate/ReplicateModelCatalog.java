package org.dual.replicate.replicate;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.dual.replicate.domain.GenerationFormType;
import org.dual.replicate.domain.GenerationKind;
import org.dual.replicate.domain.ReplicateModel;
import org.dual.replicate.repository.ReplicateModelRepository;
import org.springframework.stereotype.Component;

/**
 * Catalogo dei modelli Replicate proposti nel combobox di /generations/new
 * e /deep-chat: censiti a mano nel DB (tabella REPLICATE_MODEL, vedi
 * migrazione V6), non piu' fetchati dal vivo da Replicate a ogni avvio.
 * Tabella minuscola, nessuna cache: ogni chiamata interroga direttamente
 * {@link ReplicateModelRepository}, cosi' una riga modificata via SQL e'
 * visibile subito, senza dover riavviare l'app.
 */
@Component
public class ReplicateModelCatalog {

    private final ReplicateModelRepository repository;

    public ReplicateModelCatalog(ReplicateModelRepository repository) {
        this.repository = repository;
    }

    /** Modelli censiti attivi, ordinati per visualizzazione nel combobox. */
    public List<ReplicateModel> models() {
        return repository.findByActiveTrueOrderBySortOrderAsc();
    }

    /** Solo i modelli attivi del tipo di media dato: /deep-chat propone i soli modelli immagine. */
    public List<ReplicateModel> models(GenerationKind kind) {
        return models().stream().filter(m -> m.getFormType().kind() == kind).toList();
    }

    /** Primo modello attivo del catalogo, se ce n'e' uno: preselezionato in /generations/new e /deep-chat. */
    public Optional<ReplicateModel> defaultModel() {
        List<ReplicateModel> models = models();
        return models.isEmpty() ? Optional.empty() : Optional.of(models.get(0));
    }

    /** True se {@code id} e' un modello censito attivo che produce media del tipo dato. */
    public boolean contains(String id, GenerationKind kind) {
        return formTypeOf(id).map(t -> t.kind() == kind).orElse(false);
    }

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
     * ReplicateModelCatalog, questo valore non e' piu' risolto dal vivo
     * contro "l'ultima versione pubblicata" ma censito nella riga DB: se
     * il modello viene ripubblicato, l'hash va aggiornato con una nuova
     * migrazione.
     */
    public Optional<String> versionOf(String id) {
        return find(id).map(ReplicateModel::getVersion);
    }

    public Optional<GenerationFormType> formTypeOf(String id) {
        return find(id).map(ReplicateModel::getFormType);
    }

    public String idsAsCsv() {
        return models().stream().map(ReplicateModel::getIdentifier).collect(Collectors.joining(", "));
    }

    private Optional<ReplicateModel> find(String id) {
        return models().stream().filter(m -> m.getIdentifier().equals(id)).findFirst();
    }
}
