package org.dual.hexa.oauth2.login.domain;

/**
 * Un provider OIDC configurato: quello delle variabili {@code HX_OAUTH2_*} ({@code fromEnv}, {@code id} nullo, in sola lettura) o uno salvato dalla UI. Del
 * segreto del client qui non c'e' nulla: per un provider salvato e' un token scelto in {@code /tokens} ({@code secretTokenId}), per quello d'ambiente lo
 * conosce solo {@code IOAuthEnvironment}.
 */
public record ProviderConfig(Long id, String slug, String title, String issuerUri, String clientId, Long secretTokenId, boolean fromEnv) {
}
