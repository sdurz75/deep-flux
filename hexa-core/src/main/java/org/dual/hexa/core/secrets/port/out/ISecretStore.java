package org.dual.hexa.core.secrets.port.out;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.core.secrets.domain.Secret;

/** Persistenza dei segreti (cifrati). */
public interface ISecretStore {

    Secret save(Secret secret);

    Optional<Secret> findById(Long id);

    void delete(Secret secret);

    /** Tutti, ordinati per tipo e nome. */
    List<Secret> findAllOrdered();

    /** Quelli di un tipo, ordinati per nome. */
    List<Secret> findByType(String type);

    Optional<Secret> findByTypeAndName(String type, String name);

    /** Quelli di un tipo il cui nome inizia con {@code prefix}. */
    List<Secret> findByTypeAndNamePrefix(String type, String prefix);

    boolean existsByTypeAndName(String type, String name);

    boolean existsByTypeAndNameExcluding(String type, String name, Long excludedId);

    List<Secret> findAll();

    long count();

    void deleteAll();
}
