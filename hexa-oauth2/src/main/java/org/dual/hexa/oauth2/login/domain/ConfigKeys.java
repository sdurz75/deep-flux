package org.dual.hexa.oauth2.login.domain;

/** I nomi del modulo di configurazione {@code oauth2} (chiavi dei campi e colonne dei provider): un solo posto per l'applicazione e per l'adapter che li dichiara. */
public final class ConfigKeys {

    public static final String MODULE = "oauth2";
    public static final String ENABLED = "enabled";
    public static final String PROVIDERS = "providers";
    public static final String ALLOWED_EMAILS = "allowed-emails";
    public static final String ALLOWED_DOMAINS = "allowed-domains";
    public static final String SLUG = "slug";
    public static final String TITLE = "title";
    public static final String ISSUER = "issuer-uri";
    public static final String CLIENT_ID = "client-id";
    public static final String CLIENT_SECRET = "client-secret";

    private ConfigKeys() {
    }
}
