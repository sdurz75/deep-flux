package org.dual.replicate.app.training.port.out;

import org.dual.replicate.app.training.domain.HfAccount;

/**
 * HuggingFace, solo cio' che serve PRIMA e DOPO il training: capire a chi appartiene un token, creare il repo (privato) dove il trainer carichera' i pesi,
 * verificare che ci siano finiti. L'upload dei pesi lo fa il trainer, non l'app.
 */
public interface IHuggingFaceRepos {

    /** L'utente e il tipo del token; un token errato o scaduto e' un errore permanente. Sola lettura. */
    HfAccount whoami(String token);

    /** Crea il repo modello {@code <utente del token>/<repoName>} con la visibilita' data; uno gia' esistente va bene (idempotente). Scrive su HuggingFace. */
    void createModelRepo(String token, String repoName, boolean isPrivate);

    /** Il repo {@code utente/nome} esiste (e il token lo vede)? Sola lettura. */
    boolean repoExists(String token, String repoId);
}
