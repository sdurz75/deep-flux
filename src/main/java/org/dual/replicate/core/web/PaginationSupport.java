package org.dual.replicate.core.web;

import java.util.ArrayList;
import java.util.List;

/** Finestra di numeri di pagina condivisa dai listati paginati (fragments/core/pagination.html). */
public final class PaginationSupport {

    private PaginationSupport() {
    }

    /**
     * Numeri di pagina da mostrare (1-indexed): sempre prima e ultima
     * pagina, una finestra di una pagina prima/dopo quella corrente, con
     * {@code null} come segnaposto di ellissi per i buchi in mezzo — cosi'
     * la paginazione resta leggibile anche quando il listato cresce molto
     * invece di elencare centinaia di numeri.
     */
    public static List<Integer> window(int currentPage, int totalPages) {
        if (totalPages <= 1) {
            return List.of();
        }

        List<Integer> pages = new ArrayList<>();
        pages.add(1);

        int windowStart = Math.max(2, currentPage - 1);
        int windowEnd = Math.min(totalPages - 1, currentPage + 1);

        if (windowStart > 2) {
            pages.add(null);
        }
        for (int p = windowStart; p <= windowEnd; p++) {
            pages.add(p);
        }
        if (windowEnd < totalPages - 1) {
            pages.add(null);
        }
        pages.add(totalPages);

        return pages;
    }
}
