package org.dual.replicate.app.generation.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.app.generation.domain.GenerationFormType;
import org.dual.replicate.app.generation.domain.GenerationKind;
import org.dual.replicate.app.generation.domain.ReplicateModel;

/**
 * Catalogo dei modelli proposti nel combobox di /generations/new e /deep-chat: censiti a mano nel DB (tabella replicate_model),
 * nessuna cache (una riga modificata via SQL e' visibile subito).
 */
public interface IModelCatalog {

    /** Modelli censiti attivi, ordinati per visualizzazione nel combobox. */
    List<ReplicateModel> models();

    /** Solo i modelli attivi del tipo di media dato, ESCLUSI quelli di modifica (vedi {@link #editModels()}). */
    List<ReplicateModel> models(GenerationKind kind);

    /** Modelli attivi di modifica immagine (sorgente obbligatoria), pagina /generations/new?kind=edit. */
    List<ReplicateModel> editModels();

    /** True se {@code id} e' un modello di modifica censito attivo. */
    boolean containsEdit(String id);

    /** Primo modello attivo (immagine) del catalogo, se ce n'e' uno: preselezionato in /generations/new e /deep-chat. */
    Optional<ReplicateModel> defaultModel();

    /** True se {@code id} e' un modello censito attivo che produce media del tipo dato (e non e' di modifica). */
    boolean contains(String id, GenerationKind kind);

    boolean contains(String id);

    /** Versione censita per {@code id} ("owner/name"), se nota: va sempre passata esplicitamente alla creazione della prediction. */
    Optional<String> versionOf(String id);

    Optional<GenerationFormType> formTypeOf(String id);

    String idsAsCsv();
}
