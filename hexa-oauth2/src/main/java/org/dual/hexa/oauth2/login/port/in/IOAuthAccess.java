package org.dual.hexa.oauth2.login.port.in;

import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.domain.GateStatus;

/**
 * Il cancello di accesso con OAuth2/OIDC. Spento (default) non cambia nulla nell'app. Acceso, ogni richiesta senza una sessione autenticata da un utente
 * ammesso e' respinta (il PIN, se attivo, resta sopra: prima l'accesso, poi lo sblocco). Fallisce CHIUSO: se acceso ma senza provider o senza ammessi
 * nessuno entra, e il recupero e' {@code HX_OAUTH2_RESET}. L'interruttore, i provider e gli elenchi si modificano dalle impostazioni del modulo.
 */
public interface IOAuthAccess {

    /** {@code true} se il cancello e' acceso ora (letto a ogni richiesta: niente I/O). */
    boolean isEnabled();

    GateStatus status();

    /**
     * Le precondizioni di un salvataggio delle impostazioni ({@code IConfigModule#validate}): chi accende il cancello da UI ha un provider, un ammesso e un
     * accesso di prova riuscito; con il cancello acceso non si tolgono l'ultimo provider ne' l'ultima voce utile; {@code HX_OAUTH2_ENABLED=true} non si
     * spegne dalla UI. {@code providerRows} sono i provider salvati nella proposta, {@code allowedCount} email e domini della proposta.
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException REJECTED, con il messaggio da mostrare
     */
    void checkChange(boolean wasEnabled, boolean enable, int providerRows, int allowedCount);

    /** Recupero: spegne il cancello senza condizioni (usato all'avvio con {@code HX_OAUTH2_RESET}). */
    void reset();

    /**
     * Decide se l'utente autenticato dal provider puo' entrare: email verificata (normalizzata) uguale a una voce, oppure dominio esatto di una voce. Se entra
     * registra l'accesso e, per un'email esatta, lega al primo accesso {@code issuer}+{@code subject}.
     */
    AccessDecision authorize(String issuer, String email, boolean emailVerified, String subject);
}
