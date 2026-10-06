package org.dual.replicate.core.search.port.in;

/** Tiene l'indice semantico allineato ai dati delle {@link ISearchableSource}. */
public interface IArchiveIndex {

    /** Non blocca il chiamante; richieste durante un giro ne fanno ripartire uno solo alla fine. */
    void reindexAsync();

    /** Un giro completo (sincrono). */
    void reconcile();

    /** {@code true} mentre un giro di riconciliazione e' in corso. */
    boolean isRunning();

    /** Ricalcola l'embedding di {@code id} anche se testo e modello non sono cambiati. {@code false} se non esiste. */
    boolean reembed(String id);
}
