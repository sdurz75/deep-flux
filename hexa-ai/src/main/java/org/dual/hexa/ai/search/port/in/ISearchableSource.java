package org.dual.hexa.ai.search.port.in;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.dual.hexa.ai.search.domain.SearchableDocument;

/**
 * Punto di estensione: chi possiede dei dati da rendere ricercabili (generazioni, chat...) implementa questa interfaccia come
 * bean. {@code IArchiveIndex} li interroga a ogni riconciliazione (dentro una transazione read-only) e allinea l'indice:
 * aggiunge i documenti nuovi o cambiati, rimuove quelli dei {@link #types()} dichiarati che non compaiono piu'.
 */
public interface ISearchableSource {

    /** I {@code type} dei documenti di cui questa sorgente e' proprietaria (e che quindi la riconciliazione puo' rimuovere). */
    Set<String> types();

    /** TUTTI i documenti attuali della sorgente (testo vuoto = si salta). */
    List<SearchableDocument> documents();

    /**
     * Come il tool della chat cita un documento di un tipo di questa sorgente, dai suoi metadata ({@code refId}, {@code conversationId},
     * {@code role}...): l'etichetta con l'eventuale link, ad es. {@code [generation #12] (/generations/12)}, senza prefisso, tag ne testo.
     * Vuoto = il tipo non e' di questa sorgente (o usa la citazione generica).
     */
    default Optional<String> citation(String type, Map<String, Object> metadata) {
        return Optional.empty();
    }
}
