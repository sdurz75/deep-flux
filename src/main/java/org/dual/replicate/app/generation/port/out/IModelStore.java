package org.dual.replicate.app.generation.port.out;

import java.util.List;

import org.dual.replicate.app.generation.domain.ReplicateModel;

/** Il catalogo dei modelli censiti (tabella replicate_model). */
public interface IModelStore {

    /** Modelli attivi nell'ordine di visualizzazione voluto. */
    List<ReplicateModel> findActiveOrdered();
}
