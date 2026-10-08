package org.dual.hexa.oauth2.login.port.in;

import org.dual.hexa.oauth2.login.domain.AccessDecision;
import org.dual.hexa.oauth2.login.domain.GateStatus;

/**
 * Il cancello di accesso con OAuth2/OIDC. Spento (default) non cambia nulla nell'app. Acceso, ogni richiesta senza una sessione autenticata da un utente
 * ammesso e' respinta (il PIN, se attivo, resta sopra: prima l'accesso, poi lo sblocco). Fallisce CHIUSO: se acceso ma senza provider o senza ammessi
 * nessuno entra, e il recupero e' {@code HX_OAUTH2_RESET}.
 */
public interface IOAuthAccess {

    /** {@code true} se il cancello e' acceso ora (letto a ogni richiesta: niente I/O). */
    boolean isEnabled();

    GateStatus status();

    /**
     * Accende il cancello.
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException se manca un provider, un ammesso o un accesso di prova riuscito
     */
    void enable();

    void disable();

    /** Recupero: spegne il cancello senza condizioni (usato all'avvio con {@code HX_OAUTH2_RESET}). */
    void reset();

    /**
     * Decide se l'utente autenticato dal provider puo' entrare: email verificata (normalizzata) uguale a una voce, oppure dominio esatto di una voce. Se entra,
     * per un'email esatta lega al primo accesso {@code issuer}+{@code subject}, aggiorna l'ultimo accesso e registra l'accesso di prova riuscito.
     */
    AccessDecision authorize(String issuer, String email, boolean emailVerified, String subject);
}
