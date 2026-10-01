package org.dual.replicate.app.generation.port.in;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;

/**
 * Il binding delle form dei parametri di generazione, per i sottosistemi che le pilotano da fuori (la chat: pannello impostazioni
 * di /deep-chat). Non e' un caso d'uso dell'esagono: lo implementa l'adapter web di {@code generation}, che e' anche
 * chi converte la form diretta di /generations; qui compare solo perche' gli altri sottosistemi possono dipendere
 * soltanto da {@code port.in} e {@code domain}.
 */
public interface IGenerationForms {

    /**
     * Converte i campi sottomessi (sempre stringhe, come un submit HTML) nei parametri del form-type, nel vocabolario del provider:
     * solo i campi presenti e validi finiscono nella mappa.
     */
    Map<String, Object> parameters(GenerationFormType formType, Map<String, String> submittedFields);

    /**
     * Attributi di Model per il primo render del pannello del form-type: i valori di default dei campi piu' le opzioni extra
     * che il suo fragment si aspetta (es. le select dei token e dei LoRA anagrafati per flux-dev-lora).
     */
    Map<String, Object> formModel(GenerationFormType formType);
}
