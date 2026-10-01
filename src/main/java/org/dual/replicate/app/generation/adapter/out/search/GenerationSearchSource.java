package org.dual.replicate.app.generation.adapter.out.search;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.IArchiveIndex;
import org.dual.replicate.app.search.port.in.ISearchableSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Contributo di {@code generation} alla ricerca semantica: il prompt di ogni generazione riuscita ({@code type=generation}), e il
 * giro di riconciliazione a ogni generazione completata (l'indice non ascolta {@code generation}: e' questo adapter a chiamarlo,
 * se la ricerca e' attiva).
 */
@Component
public class GenerationSearchSource implements ISearchableSource {

    private final IGenerations generations;
    private final ObjectProvider<IArchiveIndex> index;

    public GenerationSearchSource(IGenerations generations, ObjectProvider<IArchiveIndex> index) {
        this.generations = generations;
        this.index = index;
    }

    @Override
    public Set<String> types() {
        return Set.of(DocumentTypes.GENERATION);
    }

    @Override
    public List<SearchableDocument> documents() {
        return generations.succeeded().stream().map(GenerationSearchSource::document).toList();
    }

    @EventListener
    void onGenerationCompleted(GenerationCompletedEvent event) {
        index.ifAvailable(IArchiveIndex::reindexAsync);
    }

    private static SearchableDocument document(Generation generation) {
        return SearchableDocument.of("generation:" + generation.getId(), DocumentTypes.GENERATION, generation.getId(),
                generation.getConversationId(), generation.getCreatedAt(), generation.getPrompt(),
                Map.of("kind", String.valueOf(generation.getKind())));
    }
}
