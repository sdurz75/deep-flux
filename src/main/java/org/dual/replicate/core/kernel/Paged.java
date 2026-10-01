package org.dual.replicate.core.kernel;

import java.util.List;
import java.util.function.Function;

/**
 * Una pagina di risultati indipendente dal framework di persistenza (le porte non espongono {@code Page}/{@code Pageable} di Spring
 * Data). {@code pageIndex} parte da 0.
 */
public record Paged<T>(List<T> content, int pageIndex, int pageSize, long totalElements) {

    public int totalPages() {
        return pageSize <= 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    public boolean hasPrevious() {
        return pageIndex > 0;
    }

    public boolean hasNext() {
        return pageIndex + 1 < totalPages();
    }

    public <R> Paged<R> map(Function<? super T, ? extends R> mapper) {
        return new Paged<>(content.stream().<R>map(mapper).toList(), pageIndex, pageSize, totalElements);
    }
}
