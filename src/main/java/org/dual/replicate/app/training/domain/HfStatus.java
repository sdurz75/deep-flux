package org.dual.replicate.app.training.domain;

/**
 * La copia dei pesi su HuggingFace di un training: {@code NONE} = non richiesta; {@code PENDING} = richiesta, da verificare; poi, a training riuscito, i file dei
 * pesi ci sono ({@code VERIFIED}) o no ({@code NOT_FOUND}: il repo e' pre-creato dall'app, quindi conta il CONTENUTO, non l'esistenza); {@code UNVERIFIED} = non si e'
 * potuto controllare (il token non c'e' piu' o non e' piu' valido). Un guasto qui non fa mai fallire il training.
 */
public enum HfStatus {
    NONE,
    PENDING,
    VERIFIED,
    NOT_FOUND,
    UNVERIFIED
}
