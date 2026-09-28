package org.dual.replicate.repository;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.domain.ReplicateModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplicateModelRepository extends JpaRepository<ReplicateModel, Long> {

    /** Catalogo offerto nel combobox modello: solo i censiti attivi, nell'ordine di visualizzazione voluto. */
    List<ReplicateModel> findByActiveTrueOrderBySortOrderAsc();

    Optional<ReplicateModel> findByOwnerAndName(String owner, String name);
}
