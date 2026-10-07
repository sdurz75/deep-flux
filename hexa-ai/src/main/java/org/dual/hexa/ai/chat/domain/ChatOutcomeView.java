package org.dual.hexa.ai.chat.domain;

import java.util.List;

/**
 * Come un esito gia' scritto (vedi {@link ChatOutcome}) si presenta OGGI: {@code modelNote} e' la nota per il modello, in inglese e mai
 * localizzata (arriva come turno {@code system}); {@code files} gli allegati da mostrare al ripristino della cronologia (o {@code null}).
 */
public record ChatOutcomeView(String modelNote, List<FileRef> files) {
}
