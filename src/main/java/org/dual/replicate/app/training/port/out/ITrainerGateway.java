package org.dual.replicate.app.training.port.out;

import java.util.Map;

import org.dual.replicate.app.training.domain.DatasetArchive;
import org.dual.replicate.app.training.domain.TrainerJob;

/**
 * Il servizio che addestra: oggi {@code replicate/fast-flux-trainer} su Replicate. Ogni chiamata e' remota. {@link #createTraining} e {@link #ensureDestination}
 * CREANO e costano o scrivono sull'account: non si ritentano. Un guasto e' una {@code RemoteServiceException} (transitorio, permanente...).
 */
public interface ITrainerGateway {

    /** Carica lo zip e ritorna l'URL da passare come {@code input_images}. Ritentabile: lo zip si rilegge dall'inizio. */
    String uploadFile(String filename, DatasetArchive archive);

    /** L'ultima versione del trainer (il suo id/hash), da fissare nella riga cosi' che il training si possa ricostruire. */
    String trainerVersion();

    /** Crea (se manca) un modello Replicate PRIVATO con questo nome sul proprio account e ritorna {@code owner/nome}. */
    String ensureDestination(String name);

    /** Avvia il training. A PAGAMENTO e non idempotente: mai ritentato. {@code input} e' nel vocabolario del trainer ({@code input_images}, {@code trigger_word}...). */
    TrainerJob createTraining(String trainerVersion, String destination, Map<String, Object> input);

    TrainerJob getTraining(String externalId);

    /** Chiede di interrompere un training in corso; uno gia' terminale e' un errore HTTP permanente. */
    TrainerJob cancelTraining(String externalId);
}
