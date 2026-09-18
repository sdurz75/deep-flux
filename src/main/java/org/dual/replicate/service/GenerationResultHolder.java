package org.dual.replicate.service;

import org.dual.replicate.domain.Generation;

/**
 * Canale di uscita usato da ImageGenerationTool per comunicare a
 * DeepChatService quale Generation e' stata prodotta durante una
 * chiamata a ChatClient. Serve perche' ToolContext di Spring AI e', per
 * design, solo caller -&gt; tool (mai rimandato al chiamante): un tool non
 * ha modo di "restituire" dati strutturati se non tramite il suo valore
 * di ritorno testuale, che qui invece serve come messaggio per il
 * modello. Una nuova istanza per ogni DeepChatService.reply, mai
 * condivisa tra richieste (il bean ImageGenerationTool e' invece un
 * singleton, quindi non puo' tenere questo stato in un campo proprio).
 */
public class GenerationResultHolder {

    public static final String CONTEXT_KEY = "generationResultHolder";

    private Generation generation;

    public void setGeneration(Generation generation) {
        this.generation = generation;
    }

    public Generation getGeneration() {
        return generation;
    }
}
