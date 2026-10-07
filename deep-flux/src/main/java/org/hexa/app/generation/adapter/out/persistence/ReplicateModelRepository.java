package org.hexa.app.generation.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.hexa.app.generation.domain.ReplicateModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo la porta out corrispondente. */
interface ReplicateModelRepository extends JpaRepository<ReplicateModel, Long> {

    /** Catalogo offerto nel combobox modello: solo i censiti attivi, nell'ordine di visualizzazione voluto. */
    List<ReplicateModel> findByActiveTrueOrderBySortOrderAsc();

    Optional<ReplicateModel> findByOwnerAndName(String owner, String name);

    @Query("select max(m.sortOrder) from ReplicateModel m")
    Optional<Integer> findMaxSortOrder();
}
