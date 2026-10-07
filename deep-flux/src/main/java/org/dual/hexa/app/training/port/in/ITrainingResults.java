package org.dual.hexa.app.training.port.in;

/**
 * Il risultato di un training RIUSCITO: un preset in /loras che punta al modello Replicate di destinazione, il modello censito come fine-tune (quindi utilizzabile
 * nel combobox di generazione, in chat e nelle form dei LoRA, senza token) e la copia dei pesi su HuggingFace verificata. Ogni passo e' idempotente e indipendente: un
 * guasto in uno non blocca gli altri e non fa mai fallire il training (i pesi esistono comunque).
 */
public interface ITrainingResults {

    /** Completa il risultato di un training riuscito; uno sconosciuto, non riuscito o gia' completo non fa nulla. */
    void complete(Long trainingId);

    /** Riprende i risultati rimasti a meta' (riavvio, guasto transitorio, modello non ancora visibile) dei training finiti di recente; ritorna quanti ne ha ripresi. */
    int recoverPending();
}
