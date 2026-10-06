package org.dual.replicate.core.chat.domain;

import java.util.List;

/**
 * Un esito asincrono da scrivere come turno della conversazione: {@code ref} e' il riferimento opaco (oggi l'id di una generazione,
 * vedi {@code ChatMessage#getOutcomeRef}), {@code text} il testo gia' localizzato mostrato all'utente, {@code files} gli allegati
 * (o {@code null}).
 */
public record ChatOutcome(Long ref, String text, List<FileRef> files) {
}
