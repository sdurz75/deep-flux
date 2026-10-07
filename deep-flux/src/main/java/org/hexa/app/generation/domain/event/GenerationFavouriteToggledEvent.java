package org.hexa.app.generation.domain.event;

/** La star di un file di una Generation e' cambiata (GenerationService#toggleFavourite): i metadata dell'indice di ricerca vanno riallineati. */
public record GenerationFavouriteToggledEvent(Long generationId) {
}
