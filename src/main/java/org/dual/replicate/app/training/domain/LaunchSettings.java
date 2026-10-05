package org.dual.replicate.app.training.domain;

/**
 * Le impostazioni di lancio di una bozza (si salvano con lei: riprendere una bozza riprende anche queste). {@code modelName} e' il nome di base del modello
 * Replicate di destinazione (vuoto = dal nome del dataset): al lancio ci si aggiunge sempre data e ora, perche' ogni training ha il PROPRIO modello.
 * {@code hfPublish} (copia dei pesi su HuggingFace) e' acceso di default e privato di default; {@code hfTokenId} e' un token salvato in /tokens.
 */
public record LaunchSettings(String modelName, int trainingSteps, Long seed, boolean hfPublish, Long hfTokenId, String hfRepoName, boolean hfPrivate) {

    public static final int DEFAULT_STEPS = 1000;

    /** Cio' che ha una bozza appena creata. */
    public static LaunchSettings defaults() {
        return new LaunchSettings(null, DEFAULT_STEPS, null, true, null, null, true);
    }
}
