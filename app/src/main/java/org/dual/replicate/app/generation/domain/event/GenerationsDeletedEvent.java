package org.dual.replicate.app.generation.domain.event;

import java.util.List;

/** Una o piu' Generation sono state eliminate (GenerationService#delete/#deleteAll), qualunque sia la vista da cui e' partita la cancellazione. */
public record GenerationsDeletedEvent(List<Long> ids) {
}
