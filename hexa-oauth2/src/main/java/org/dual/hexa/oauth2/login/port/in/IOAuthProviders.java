package org.dual.hexa.oauth2.login.port.in;

import java.util.List;
import java.util.Optional;
import org.dual.hexa.oauth2.login.domain.ProviderConfig;

/** I provider OIDC: quello d'ambiente (primo, in sola lettura) e quelli salvati dalla UI. */
public interface IOAuthProviders {

    /** Nome del provider di token (e chiave {@code tokens.provider.OAUTH2}) con cui si salva il segreto del client in {@code /tokens}. */
    String TOKEN_PROVIDER = "OAUTH2";

    List<ProviderConfig> list();

    Optional<ProviderConfig> find(String slug);

    /**
     * Salva un provider. L'issuer e' l'URL da cui si legge {@code /.well-known/openid-configuration}; il segreto e' un token {@code OAUTH2} di {@code /tokens}.
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException se un campo non e' valido, lo slug esiste gia' o il token non esiste
     */
    ProviderConfig create(String slug, String title, String issuerUri, String clientId, Long secretTokenId);

    /**
     * Cancella un provider salvato.
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException se il cancello e' acceso ed e' l'ultimo provider
     */
    void delete(Long id);
}
