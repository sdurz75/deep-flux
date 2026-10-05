package org.dual.replicate.core.backup.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Ordine di caricamento delle tabelle: ognuna dopo quelle che referenzia con una FK (Kahn). I riferimenti a se' stessa (es.
 * {@code generation.source_generation_id}) non contano: i controlli delle FK non differibili scattano a fine statement, quindi un
 * unico {@code COPY} puo' contenere righe e genitori. A parita' di condizioni l'ordine e' alfabetico, quindi stabile.
 */
public final class TableOrder {

    private TableOrder() {
    }

    /**
     * @param tables    tutte le tabelle
     * @param parentsOf per ogni tabella, quelle che referenzia (le assenti da {@code tables} si ignorano)
     * @throws IllegalStateException se restano tabelle che si referenziano a vicenda (il messaggio le elenca)
     */
    public static List<String> sort(Collection<String> tables, Map<String, ? extends Collection<String>> parentsOf) {
        Map<String, Set<String>> pending = new TreeMap<>();
        for (String table : tables) {
            Set<String> parents = new TreeSet<>();
            Collection<String> declared = parentsOf.get(table);
            for (String parent : declared == null ? List.<String>of() : declared) {
                if (!parent.equals(table) && tables.contains(parent)) {
                    parents.add(parent);
                }
            }
            pending.put(table, parents);
        }
        List<String> ordered = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            List<String> ready = pending.entrySet().stream().filter(e -> done.containsAll(e.getValue())).map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException(String.join(", ", pending.keySet()));
            }
            ready.forEach(pending::remove);
            ordered.addAll(ready);
            done.addAll(ready);
        }
        return ordered;
    }
}
