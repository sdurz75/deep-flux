package org.dual.hexa.app.dashboard.port.in;

import java.util.List;
import java.util.Map;

import org.dual.hexa.ai.chat.domain.ChatStats;
import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.domain.GenerationStats;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.core.events.port.in.ISystemEvents;

/**
 * I dati della dashboard della home: una sintesi di sola lettura di generazioni, archivio, chat, training ed eventi. Solo dati locali:
 * nessuna chiamata remota (il credito residuo si carica a parte, da {@code ICredits}, perche' interroga OpenRouter).
 */
public interface IDashboard {

    /** Ampiezza in giorni della finestra delle statistiche (attivita', costi, modelli piu' usati). */
    int WINDOW_DAYS = 30;

    /** Quante immagini recenti mostra la dashboard. */
    int RECENT_IMAGES = 6;

    /**
     * @param generation statistiche delle generazioni sugli ultimi {@link #WINDOW_DAYS} giorni
     * @param chat       conteggi di /deep-chat
     * @param trainings  training per stato (solo gli stati presenti)
     * @param loras      LoRA anagrafati in /loras
     * @param recent     le ultime immagini/video riusciti (un file per generazione), piu' recenti prima
     * @param attention  eventi di sistema non letti (almeno WARNING) con gli ultimi
     */
    record View(GenerationStats generation, ChatStats chat, Map<TrainingStatus, Long> trainings, int loras,
                List<GalleryItem> recent, ISystemEvents.Unseen attention) {

        /** Training non ancora terminali. */
        public long trainingsRunning() {
            return count(TrainingStatus.PENDING) + count(TrainingStatus.PROCESSING);
        }

        public long trainingsSucceeded() {
            return count(TrainingStatus.SUCCEEDED);
        }

        public long trainingsTotal() {
            return trainings.values().stream().mapToLong(Long::longValue).sum();
        }

        private long count(TrainingStatus status) {
            return trainings.getOrDefault(status, 0L);
        }
    }

    View view();
}
