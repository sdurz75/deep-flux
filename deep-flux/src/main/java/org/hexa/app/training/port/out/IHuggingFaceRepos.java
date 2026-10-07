package org.hexa.app.training.port.out;

import java.util.List;
import java.util.Optional;

import org.hexa.app.training.domain.HfAccount;
import org.hexa.app.training.domain.WeightsFile;

/**
 * HuggingFace, solo cio' che serve PRIMA e DOPO il training: capire a chi appartiene un token, creare il repo (privato) dove il trainer carichera' i pesi,
 * verificare che ci siano finiti. L'upload dei pesi lo fa di norma il trainer; l'app lo fa solo a mano, come rimedio ({@link #uploadWeights}).
 */
public interface IHuggingFaceRepos {

    /** L'utente e il tipo del token; un token errato o scaduto e' un errore permanente. Sola lettura. */
    HfAccount whoami(String token);

    /** Crea il repo modello {@code <utente del token>/<repoName>} con la visibilita' data; uno gia' esistente va bene (idempotente). Scrive su HuggingFace. */
    void createModelRepo(String token, String repoName, boolean isPrivate);

    /**
     * I file del repo {@code utente/nome} (nomi relativi), o vuoto se il repo non esiste (o il token non lo vede). Sola lettura. Serve a controllare che i pesi ci
     * siano finiti: il repo lo crea l'app PRIMA del training, quindi la sua sola esistenza non prova nulla.
     */
    Optional<List<String>> repoFiles(String token, String repoId);

    /**
     * Carica i pesi nel repo {@code utente/nome} (ramo principale) come un solo commit, col nome del file: LFS, in streaming, senza tenerli in memoria. Se HuggingFace
     * li ha gia' (stesso contenuto) non li rimanda. Scrive su HuggingFace. Il repo deve esistere (un 404 e' un errore): non lo crea.
     */
    void uploadWeights(String token, String repoId, WeightsFile weights, String commitMessage);
}
