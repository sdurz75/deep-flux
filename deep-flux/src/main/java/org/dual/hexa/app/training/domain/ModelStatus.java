package org.dual.hexa.app.training.domain;

/**
 * Se il modello Replicate nato da un training riuscito e' utilizzabile nell'app (censito come fine-tune di Flux: compare nel combobox di generazione, in chat, nella
 * form di flux-dev-lora): {@code PENDING} = da fare o da rifare, {@code REGISTERED} = fatto, {@code REJECTED} = impossibile (si smette di ritentare, un avviso lo dice).
 */
public enum ModelStatus {
    PENDING,
    REGISTERED,
    REJECTED
}
