package org.dual.replicate.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Canale di uscita usato da ImageGenerationTool per comunicare a
 * DeepChatService quali generazioni sono state avviate durante una
 * chiamata a ChatClient. Serve perche' ToolContext di Spring AI e', per
 * design, solo caller -&gt; tool (mai rimandato al chiamante): un tool non
 * ha modo di "restituire" dati strutturati se non tramite il suo valore
 * di ritorno testuale, che qui invece serve come messaggio per il
 * modello. Una nuova istanza per ogni DeepChatService.reply, mai
 * condivisa tra richieste (il bean ImageGenerationTool e' invece un
 * singleton, quindi non puo' tenere questo stato in un campo proprio).
 *
 * Solo gli id vengono raccolti, non le Generation stesse: il tool ritorna
 * subito dopo aver avviato la generazione (non ne attende piu' l'esito),
 * quindi reply() avvia un watch in background per ciascun id (vedi
 * DeepChatGenerationWatcher) invece di leggere qui un risultato gia'
 * pronto. Lista thread-safe: l'LLM puo' chiamare il tool piu' volte nello
 * stesso turno.
 */
public class GenerationResultHolder {

    public static final String CONTEXT_KEY = "generationResultHolder";

    private final List<Long> startedGenerationIds = new CopyOnWriteArrayList<>();

    public void addStartedGeneration(Long generationId) {
        startedGenerationIds.add(generationId);
    }

    public List<Long> getStartedGenerationIds() {
        return startedGenerationIds;
    }
}
