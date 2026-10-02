package org.dual.replicate.app.generation.adapter.out.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.event.GenerationCompletedEvent;
import org.dual.replicate.app.generation.domain.event.GenerationFavouriteToggledEvent;
import org.dual.replicate.app.generation.domain.event.GenerationImageDeletedEvent;
import org.dual.replicate.app.generation.domain.event.GenerationsDeletedEvent;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.dual.replicate.app.generation.port.in.ILoraPresets;
import org.dual.replicate.app.search.domain.DocumentTypes;
import org.dual.replicate.app.search.domain.SearchableDocument;
import org.dual.replicate.app.search.port.in.IArchiveIndex;
import org.dual.replicate.app.search.port.in.ISearchableSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import tools.jackson.databind.ObjectMapper;

/**
 * Contributo di {@code generation} alla ricerca semantica: UN documento per ogni generazione riuscita ({@code type=generation}) col
 * prompt piu' le tag d'indice ({@link GenerationSearchText}) e, nei metadata, cio' che serve a filtrare e a mostrare i file trovati
 * ({@code kind}, {@code model}, {@code favourite}, {@code files}, {@code favouriteFiles}, {@code outputs}). Inoltre il giro di
 * riconciliazione quando i dati indicizzati cambiano (l'indice non ascolta {@code generation}: e' questo adapter a chiamarlo, se la
 * ricerca e' attiva): generazione completata, file o generazioni cancellati, star cambiata.
 */
@Component
public class GenerationSearchSource implements ISearchableSource {

    /** Quante miniature porta un documento nei metadata. */
    static final int MAX_FILES = 4;

    private final IGenerations generations;
    private final ILoraPresets loraPresets;
    private final ObjectProvider<IArchiveIndex> index;
    private final ObjectMapper objectMapper;

    public GenerationSearchSource(IGenerations generations, ILoraPresets loraPresets, ObjectProvider<IArchiveIndex> index,
                                  ObjectMapper objectMapper) {
        this.generations = generations;
        this.loraPresets = loraPresets;
        this.index = index;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> types() {
        return Set.of(DocumentTypes.GENERATION);
    }

    @Override
    public List<SearchableDocument> documents() {
        List<ILoraPresets.LoraView> presets = loraPresets.list();
        return generations.succeeded().stream().map(generation -> document(generation, presets)).toList();
    }

    // Dopo il commit (la riconciliazione gira su un altro thread e deve leggere lo stato nuovo); senza transazione in corso scatta subito.
    @TransactionalEventListener(fallbackExecution = true)
    void onGenerationCompleted(GenerationCompletedEvent event) {
        reindex();
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onImageDeleted(GenerationImageDeletedEvent event) {
        reindex();
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onGenerationsDeleted(GenerationsDeletedEvent event) {
        reindex();
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onFavouriteToggled(GenerationFavouriteToggledEvent event) {
        reindex();
    }

    private void reindex() {
        index.ifAvailable(IArchiveIndex::reindexAsync);
    }

    private SearchableDocument document(Generation generation, List<ILoraPresets.LoraView> presets) {
        Set<String> favourites = generation.getFavouriteFilenames();
        // Prima i file con la star: la miniatura mostrata e' quella che l'utente ha scelto.
        List<String> files = new ArrayList<>(new LinkedHashSet<>(generation.getImageFilenames()));
        files.sort((a, b) -> Boolean.compare(favourites.contains(b), favourites.contains(a)));
        List<String> shown = files.subList(0, Math.min(MAX_FILES, files.size()));

        Map<String, Object> extra = new HashMap<>();
        extra.put("kind", String.valueOf(generation.getKind()));
        extra.put("model", generation.getModel());
        extra.put("favourite", !favourites.isEmpty());
        extra.put("outputs", files.size());
        extra.put("files", List.copyOf(shown));
        extra.put("favouriteFiles", shown.stream().filter(favourites::contains).toList());
        return SearchableDocument.of("generation:" + generation.getId(), DocumentTypes.GENERATION, generation.getId(),
                generation.getConversationId(), generation.getCreatedAt(), GenerationSearchText.of(generation, presets, objectMapper),
                extra);
    }
}
