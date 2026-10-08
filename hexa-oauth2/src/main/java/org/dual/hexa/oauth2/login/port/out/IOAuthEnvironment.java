package org.dual.hexa.oauth2.login.port.out;

import java.util.Optional;

/**
 * La parte della configurazione che NON passa dalle impostazioni del modulo: il provider d'ambiente ({@code HX_OAUTH2_PROVIDER}, {@code _ISSUER_URI},
 * {@code _CLIENT_ID}, {@code _CLIENT_SECRET}, {@code _TITLE}: in sola lettura, il segreto non si salva), il recupero {@code HX_OAUTH2_RESET} e la richiesta
 * del deployer {@code HX_OAUTH2_ENABLED=true}, che e' AUTOREVOLE (la UI non lo spegne). Gli elenchi degli ammessi e l'interruttore sono campi di
 * {@code IConfigModule} (con la stessa variabile come alias).
 */
public interface IOAuthEnvironment {

    /** {@code HX_OAUTH2_ENABLED=true}: il deployer chiede il cancello acceso (senza le precondizioni della UI: la responsabilita' e' sua). */
    boolean gateEnabled();

    /** {@code HX_OAUTH2_RESET}: spegne il cancello all'avvio e lo tiene spento finche' resta impostata. */
    boolean reset();

    /** Il provider d'ambiente, se issuer, client id e segreto sono tutti presenti. */
    Optional<EnvProvider> provider();

    /** Il provider d'ambiente. {@code toString} non contiene mai il segreto. */
    record EnvProvider(String slug, String title, String issuerUri, String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "EnvProvider[slug=" + slug + ", issuerUri=" + issuerUri + ", clientId=" + clientId + "]";
        }
    }
}
