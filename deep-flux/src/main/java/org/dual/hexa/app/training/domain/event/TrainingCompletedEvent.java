package org.dual.hexa.app.training.domain.event;

/**
 * Un training e' RIUSCITO: ora serve il suo risultato (preset in /loras, modello utilizzabile, copia su HuggingFace verificata). Pubblicato una volta, alla
 * transizione, da qualunque strada porti a "riuscito" (il poll, l'annullamento che trova il training gia' finito, il recupero).
 */
public record TrainingCompletedEvent(Long trainingId) {
}
