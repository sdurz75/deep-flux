package org.dual.replicate.replicate;

/**
 * Rifiuto applicativo (non un errore di Replicate) quando ci sono gia'
 * troppe prediction in esecuzione sull'account: vedi
 * GenerationService#create. Sottoclasse di ReplicateException cosi' i
 * controller che gia' catturano quest'ultima (GenerationController) la
 * gestiscono senza modifiche.
 */
public class TooManyPredictionsException extends ReplicateException {

    public TooManyPredictionsException(String message) {
        super(message);
    }
}
