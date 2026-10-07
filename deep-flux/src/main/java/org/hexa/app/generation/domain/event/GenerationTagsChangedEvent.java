package org.hexa.app.generation.domain.event;

/** I tag utente di una Generation o di un suo file sono cambiati (GenerationService#addTag/#removeTag): l'indice di ricerca va riallineato. */
public record GenerationTagsChangedEvent(Long generationId) {
}
