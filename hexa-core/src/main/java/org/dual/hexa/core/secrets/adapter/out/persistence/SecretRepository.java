package org.dual.hexa.core.secrets.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.dual.hexa.core.secrets.domain.Secret;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code ISecretStore}. */
interface SecretRepository extends JpaRepository<Secret, Long> {

    List<Secret> findAllByOrderByTypeAscNameAsc();

    List<Secret> findAllByTypeOrderByNameAsc(String type);

    Optional<Secret> findByTypeAndName(String type, String name);

    List<Secret> findAllByTypeAndNameStartingWith(String type, String prefix);

    boolean existsByTypeAndNameIgnoreCase(String type, String name);

    boolean existsByTypeAndNameIgnoreCaseAndIdNot(String type, String name, Long id);
}
