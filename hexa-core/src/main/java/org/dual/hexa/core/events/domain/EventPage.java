package org.dual.hexa.core.events.domain;

import java.util.List;

/** Una pagina del registro eventi (piu' recente prima), senza tipi di paginazione del framework. */
public record EventPage(List<SystemEvent> content, int totalPages, boolean hasPrevious, boolean hasNext,
                        long totalElements) {

    public boolean isEmpty() {
        return content.isEmpty();
    }
}
