package org.hexa.app.generation.port.out;

import java.util.List;

import org.hexa.app.generation.domain.ReplicateModel;

/** Il catalogo dei modelli censiti (tabella replicate_model). */
public interface IModelStore {

    /** Modelli attivi nell'ordine di visualizzazione voluto. */
    List<ReplicateModel> findActiveOrdered();

    /** True se il modello e' censito, attivo o no. */
    boolean exists(String owner, String name);

    /** Posizione di visualizzazione del prossimo modello censito: dopo tutti gli esistenti. */
    int nextSortOrder();

    ReplicateModel save(ReplicateModel model);
}
