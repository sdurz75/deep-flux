package org.hexa.app.training.domain;

/**
 * Lo stato di un training su Replicate visto dallo use case: {@code error} e {@code logs} solo se il servizio li ha dati, {@code predictTimeSeconds} (il tempo di
 * calcolo, solo a training finito) serve a mostrare quanto e' durato, {@code version} e' la versione del trainer che Replicate sta ESEGUENDO (puo' non essere
 * quella richiesta: il primo training vero e' girato su {@code 56cb4a64} avendo chiesto {@code e5a5bc82}). Mai l'{@code input} della risposta: porta il token
 * HuggingFace.
 */
public record TrainerJob(String externalId, TrainingStatus status, String error, String logs, Double predictTimeSeconds, String version) {

    /** Senza la versione in esecuzione (la risposta non la riportava, o non serve). */
    public TrainerJob(String externalId, TrainingStatus status, String error, String logs, Double predictTimeSeconds) {
        this(externalId, status, error, logs, predictTimeSeconds, null);
    }
}
