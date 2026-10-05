package org.dual.replicate.app.training.domain;

/**
 * Lo stato di un training su Replicate visto dallo use case: {@code error} e {@code logs} solo se il servizio li ha dati, {@code predictTimeSeconds} (il tempo di
 * calcolo, solo a training finito) serve a mostrare quanto e' durato. Mai l'{@code input} della risposta: porta il token HuggingFace.
 */
public record TrainerJob(String externalId, TrainingStatus status, String error, String logs, Double predictTimeSeconds) {
}
