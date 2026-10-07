package org.hexa.app.generation.port.in;

import java.util.Map;

import org.hexa.app.generation.domain.GenerationFormType;

/**
 * Il binding delle form dei parametri di generazione: converte i campi di una form (stringhe) nei parametri tipizzati del
 * provider e dice quali valori ha una form e quali opzioni extra le servono. Lo usano la form diretta di /generations e il
 * pannello impostazioni di /deep-chat (la chat, che e' un altro sottosistema, lo vede solo da questa porta).
 */
public interface IGenerationForms {

    /**
     * Limite GLOBALE di immagini per richiesta ({@code num_outputs}) per ogni form-type che ne accetta piu' d'una: Replicate non ne
     * supporta di piu'. Lo applica il server (clamp 1..4 nei parametri) e lo leggono i fragment dei form-type per l'attributo {@code max}.
     */
    int MAX_NUM_OUTPUTS = 4;

    /** Chiave Model con i soli default dei campi (chiavi = nomi dei campi): i numerici li portano in {@code data-default} per il reset. */
    String FIELD_DEFAULTS = "fieldDefaults";

    /**
     * Converte i campi sottomessi (sempre stringhe, come un submit HTML) nei parametri del form-type, nel vocabolario del provider:
     * solo i campi presenti e validi finiscono nella mappa.
     */
    Map<String, Object> parameters(GenerationFormType formType, Map<String, String> submittedFields);

    /**
     * Inverso di {@link #parameters}: i parametri salvati di una generazione (vocabolario del provider) diventano campi del form
     * (nomi e stringhe del form), per riproporla. Le chiavi che il form-type non conosce si scartano; solo per il render, mai da
     * ripassare a {@link #parameters}.
     */
    Map<String, String> formFields(GenerationFormType formType, Map<String, Object> parameters);

    /** I valori di default dei campi del form-type (nessun campo ancora sottomesso); le chiavi sono quelle che la form conserva. */
    Map<String, Object> defaultFields(GenerationFormType formType);

    /**
     * Opzioni extra che il fragment del form-type si aspetta (es. le select dei token e dei LoRA anagrafati per flux-dev-lora):
     * vuote per gli altri, calcolate solo dove servono.
     */
    Map<String, Object> extraFormOptions(GenerationFormType formType);

    /**
     * Attributi di Model per il primo render del pannello del form-type: i valori di default dei campi piu' le opzioni extra
     * che il suo fragment si aspetta (es. le select dei token e dei LoRA anagrafati per flux-dev-lora), piu' gli stessi default
     * sotto {@link #FIELD_DEFAULTS} (separati dai valori correnti: servono al reset al default dei campi numerici).
     */
    Map<String, Object> formModel(GenerationFormType formType);
}
