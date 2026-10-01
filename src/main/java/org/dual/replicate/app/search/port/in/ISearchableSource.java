package org.dual.replicate.app.search.port.in;

import java.util.List;
import java.util.Set;

import org.dual.replicate.app.search.domain.SearchableDocument;

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
}
