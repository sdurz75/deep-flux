package org.dual.hexa.app.generation.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.app.generation.domain.GenerationFormType;
import org.dual.hexa.app.generation.domain.GenerationKind;
import org.dual.hexa.app.generation.domain.ReplicateModel;

/**
 * Catalogo dei modelli proposti nel combobox di /generations/new e /deep-chat: censiti a mano nel DB (tabella replicate_model),
 * nessuna cache (una riga modificata via SQL e' visibile subito).
 */
public interface IModelCatalog {

    /** Modelli censiti attivi, ordinati per visualizzazione nel combobox. */
    List<ReplicateModel> models();

    /**
     * Solo i modelli attivi del tipo di media dato che funzionano SENZA una sorgente (vedi {@link GenerationFormType#sourceRequired()}):
     * quelli che puo' usare /deep-chat e il default di /generations/new. Per il combobox del form vedi {@link #formModels(GenerationKind)}.
     */
    List<ReplicateModel> models(GenerationKind kind);

    /** Tutti i modelli attivi del tipo di media dato, anche quelli a sorgente obbligatoria: il combobox di /generations/new. */
    List<ReplicateModel> formModels(GenerationKind kind);

    /** Primo modello attivo (immagine) del catalogo, se ce n'e' uno: preselezionato in /generations/new e /deep-chat. */
    Optional<ReplicateModel> defaultModel();

    /** True se {@code id} e' un modello censito attivo che produce media del tipo dato (e non richiede una sorgente). */
    boolean contains(String id, GenerationKind kind);

    boolean contains(String id);

    /** Versione censita per {@code id} ("owner/name"), se nota: va sempre passata esplicitamente alla creazione della prediction. */
    Optional<String> versionOf(String id);

    Optional<GenerationFormType> formTypeOf(String id);

    String idsAsCsv();

    /**
     * Censisce {@code identifier} ("owner/nome", un LoRA addestrato su Replicate) come modello {@code FLUX_LORA_FINETUNE}, con l'ultima
     * versione letta da Replicate (pinnata): da quel momento compare ovunque compaia ogni altro fine-tune. Idempotente: gia' censito
     * (attivo o no) = nessuna modifica, vuoto. Un modello inesistente o che non e' un LoRA di Flux e' un rifiuto
     * ({@code ReplicateException} REJECTED); un errore remoto risale com'e'.
     */
    Optional<ReplicateModel> registerLoraFinetune(String identifier, String description);
}
