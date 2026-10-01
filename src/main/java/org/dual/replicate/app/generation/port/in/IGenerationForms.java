package org.dual.replicate.app.generation.port.in;

import java.util.Map;

import org.dual.replicate.app.generation.domain.GenerationFormType;

/**
 * Le form dei parametri di generazione, una per {@link GenerationFormType}: l'handler che costruisce l'input del provider e sa quale
 * fragment renderizza i campi, piu' i dati extra che quel fragment si aspetta nel Model.
 */
public interface IGenerationForms {

    /** L'handler del form-type (un'implementazione per form-type). */
    IGenerationParameterHandler get(GenerationFormType formType);

    /**
     * Attributi di Model aggiuntivi richiesti dal fragment del form-type (es. le select dei token e dei LoRA anagrafati per
     * flux-dev-lora): vuoti per gli altri, mai calcolati a ogni richiesta se il fragment non li usa.
     */
    Map<String, Object> extraFormOptions(GenerationFormType formType);
}
