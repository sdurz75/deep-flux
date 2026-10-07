package org.dual.hexa.app.training.port.in;

/**
 * Il lavoro in background che scrive la didascalia automatica di un'immagine, e il suo recupero. Lo pilotano il listener asincrono
 * ({@code CaptionRequestedEvent}) e lo scheduler di recupero: nessuna pagina lo chiama.
 */
public interface ICaptionJobs {

    /**
     * Scrive la didascalia automatica dell'immagine, se e' ancora in sospeso. IDEMPOTENTE e mai propaga: gira in un thread in background, quindi un'immagine
     * sparita nel frattempo, una bozza congelata o una didascalia gia' scritta (da un altro lavoro, o a mano) non sono errori ma un "niente da fare".
     */
    void caption(Long datasetId, Long imageId);

    /** Rilancia le didascalie rimaste in sospeso (riavvio a meta', evento perso, coda piena): sicuro da chiamare piu' volte. @return quante ne ha rilanciate */
    int recoverPending();
}
