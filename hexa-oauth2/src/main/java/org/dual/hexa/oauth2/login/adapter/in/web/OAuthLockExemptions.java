package org.dual.hexa.oauth2.login.adapter.in.web;

import java.util.List;
import org.dual.hexa.core.lock.port.in.ILockExemptPaths;
import org.springframework.stereotype.Component;

/**
 * Con il blocco con PIN attivo la pagina di accesso e l'avvio del flusso OIDC devono passare anche a sessione bloccata: l'accesso OAuth2 viene PRIMA del
 * PIN, quindi {@code /unlock} richiede di essere gia' autenticati. Senza questa esenzione {@code /oauth2/login} verrebbe rimandata a {@code /unlock} dal
 * blocco e {@code /unlock} a {@code /oauth2/login} dal cancello OAuth2: un giro infinito (ERR_TOO_MANY_REDIRECTS).
 */
@Component
class OAuthLockExemptions implements ILockExemptPaths {

    @Override
    public List<String> paths() {
        return List.of(OAuthRequests.LOGIN_PATH, "/oauth2/start/");
    }
}
