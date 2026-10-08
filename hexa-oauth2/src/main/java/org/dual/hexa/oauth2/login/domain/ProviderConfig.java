package org.dual.hexa.oauth2.login.domain;

/**
 * Un provider OIDC configurato: quello delle variabili {@code HX_OAUTH2_*} ({@code fromEnv}, in sola lettura) o una riga dei provider salvati nelle
 * impostazioni del modulo. Del segreto del client qui non c'e' nulla: lo restituisce solo {@code IOAuthProviders#clientSecret}.
 */
public record ProviderConfig(String slug, String title, String issuerUri, String clientId, boolean fromEnv) {
}
