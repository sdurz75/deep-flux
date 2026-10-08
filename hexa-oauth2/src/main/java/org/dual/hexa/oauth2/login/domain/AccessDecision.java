package org.dual.hexa.oauth2.login.domain;

/** Esito del controllo di un utente autenticato dal provider contro la lista degli ammessi. */
public record AccessDecision(boolean allowed, Reason reason) {

    public enum Reason { OK, EMAIL_MISSING, EMAIL_UNVERIFIED, NOT_LISTED, IDENTITY_MISMATCH }

    public static AccessDecision allow() {
        return new AccessDecision(true, Reason.OK);
    }

    public static AccessDecision deny(Reason reason) {
        return new AccessDecision(false, reason);
    }
}
