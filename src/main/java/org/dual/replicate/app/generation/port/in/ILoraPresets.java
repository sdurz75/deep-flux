package org.dual.replicate.app.generation.port.in;

import java.util.List;
import java.util.Map;

/**
 * CRUD dei LoRA anagrafati. Sono solo preset di compilazione per le form di flux-dev-lora (sorgente + intensita' + trigger
 * words): la form invia comunque testo e scala, quindi cancellare o modificare un preset non tocca le generazioni passate.
 */
public interface ILoraPresets {

    int MAX_NAME = 60;
    int MAX_SOURCE = 500;
    int MAX_TEXT = 500;
    /** Stessi estremi del campo {@code lora_scale} della form. */
    double MIN_SCALE = -1;
    double MAX_SCALE = 3;
    double DEFAULT_SCALE = 1;

    /** Vista per la UI e per le select delle form. */
    record LoraView(Long id, String name, String source, double scale, String triggerWords, String note) {
    }

    List<LoraView> list();

    /** Attributi di Model per le select di preset delle form di generazione ({@code loraPresets}): solo dove serve. */
    Map<String, List<LoraView>> formOptions();

    LoraView get(Long id);

    LoraView create(String name, String source, Double scale, String triggerWords, String note);

    LoraView update(Long id, String name, String source, Double scale, String triggerWords, String note);

    void delete(Long id);
}
