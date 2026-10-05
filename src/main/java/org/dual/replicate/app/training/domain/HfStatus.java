package org.dual.replicate.app.training.domain;

/** La copia dei pesi su HuggingFace di un training: {@code NONE} = non richiesta; poi, a training riuscito, trovata o no. Un guasto qui non fa fallire il training. */
public enum HfStatus {
    NONE,
    PENDING,
    VERIFIED,
    NOT_FOUND
}
