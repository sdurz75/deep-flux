package org.dual.hexa.oauth2.login.port.out;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.OAuthProvider;

public interface IProviderStore {

    List<OAuthProvider> findAll();

    Optional<OAuthProvider> findById(Long id);

    Optional<OAuthProvider> findBySlug(String slug);

    long count();

    OAuthProvider save(OAuthProvider provider);

    void delete(OAuthProvider provider);
}
