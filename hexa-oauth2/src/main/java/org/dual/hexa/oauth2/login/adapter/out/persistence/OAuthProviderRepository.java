package org.dual.hexa.oauth2.login.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

/** Dettaglio di persistenza: fuori dall'adapter si usa solo {@code IProviderStore}. */
interface OAuthProviderRepository extends JpaRepository<OAuthProvider, Long> {

    List<OAuthProvider> findAllByOrderBySlugAsc();

    Optional<OAuthProvider> findBySlug(String slug);
}
