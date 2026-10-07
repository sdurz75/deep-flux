package org.dual.hexa.app.training.domain;

/**
 * Il dataset e' stato modificato da qualcun altro dopo che e' stato letto (blocco ottimistico): il salvataggio o l'eliminazione e' stato rifiutato e chi
 * chiama puo' rileggere e riapplicare. E' il modo in cui la porta {@code ITrainingDatasetStore} dice "conflitto" senza esporre l'eccezione della
 * tecnologia di persistenza.
 */
public class DatasetConflictException extends RuntimeException {

    public DatasetConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
