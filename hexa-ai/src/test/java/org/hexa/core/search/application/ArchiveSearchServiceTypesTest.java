package org.hexa.core.search.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.hexa.core.search.domain.SearchableDocument;
import org.hexa.core.search.port.in.IArchiveIndex;
import org.hexa.core.search.port.in.ISearchableSource;
import org.hexa.core.search.port.out.IVectorIndex;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** I tipi e le citazioni sono delle sorgenti: la ricerca possiede solo {@code note}. */
class ArchiveSearchServiceTypesTest {

    private static ISearchableSource source(String citationType, String... types) {
        return new ISearchableSource() {
            @Override
            public Set<String> types() {
                return new LinkedHashSet<>(List.of(types));
            }

            @Override
            public List<SearchableDocument> documents() {
                return List.of();
            }

            @Override
            public Optional<String> citation(String type, Map<String, Object> metadata) {
                return type.equals(citationType) ? Optional.of("[" + type + " custom]") : Optional.empty();
            }
        };
    }

    private final ArchiveSearchService service = new ArchiveSearchService(mock(IVectorIndex.class), mock(IArchiveIndex.class),
            List.of(source("a", "a", "b"), source("c", "c", "b")));

    @Test
    void typesFollowTheSourcesOrderWithoutDuplicatesAndEndWithNotes() {
        assertThat(service.types()).containsExactly("a", "b", "c", "note");
    }

    @Test
    void theCitationComesFromTheOwningSourceOrFallsBackToAGenericOne() {
        assertThat(service.citation("c", Map.of("refId", 1))).isEqualTo("[c custom]");
        assertThat(service.citation("b", Map.of("refId", 9))).isEqualTo("[b #9]");
    }
}
