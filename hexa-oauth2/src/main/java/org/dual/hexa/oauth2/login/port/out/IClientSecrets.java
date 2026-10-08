package org.dual.hexa.oauth2.login.port.out;

/** I segreti dei client OIDC salvati dalla UI: token cifrati del core. Il chiaro vive solo dentro la costruzione della registrazione. */
public interface IClientSecrets {

    /** {@code true} se il token esiste (e non e' scaduto). */
    boolean exists(Long tokenId);

    /**
     * Il segreto in chiaro.
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException REJECTED se il token non esiste o e' scaduto, CONFIGURATION se manca la chiave di cifratura
     */
    String resolve(Long tokenId);
}
