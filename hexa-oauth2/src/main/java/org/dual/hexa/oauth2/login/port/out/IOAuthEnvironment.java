package org.dual.hexa.oauth2.login.port.out;

import java.util.List;
import java.util.Optional;

/**
 * La configurazione essenziale data dal deployer con le variabili {@code HX_OAUTH2_*} (o le property {@code app.oauth2.*}): non si modifica a runtime, non si
 * salva nel DB. Le liste degli ammessi sono AGGIUNTE a quelle salvate (valgono anche come recupero se la lista salvata e' sbagliata).
 */
public interface IOAuthEnvironment {

    /** {@code HX_OAUTH2_ENABLED}: il deployer chiede il cancello acceso (senza le precondizioni della UI: la responsabilita' e' sua). */
    boolean gateEnabled();

    /** {@code HX_OAUTH2_RESET}: spegne il cancello all'avvio e lo tiene spento finche' resta impostata. */
    boolean reset();

    /** Il provider d'ambiente, se issuer, client id e segreto sono tutti presenti. */
    Optional<EnvProvider> provider();

    /** Email ammesse, gia' normalizzate (minuscolo, senza spazi). */
    List<String> allowedEmails();

    /** Domini ammessi, gia' normalizzati (minuscolo, senza chiocciola iniziale). */
    List<String> allowedDomains();

    /** Il provider d'ambiente. {@code toString} non contiene mai il segreto. */
    record EnvProvider(String slug, String title, String issuerUri, String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "EnvProvider[slug=" + slug + ", issuerUri=" + issuerUri + ", clientId=" + clientId + "]";
        }
    }
}
