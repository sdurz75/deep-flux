package org.dual.hexa.oauth2.login.domain;

/**
 * Stato del cancello. {@code enabled} e' quello effettivo; {@code requestedByEnv} dice che lo ha chiesto {@code HX_OAUTH2_ENABLED}; {@code resetActive}
 * che {@code HX_OAUTH2_RESET} lo tiene spento. {@code canEnable} = c'e' un provider, c'e' almeno un ammesso e almeno un accesso di prova e' riuscito.
 */
public record GateStatus(boolean enabled, boolean requestedByEnv, boolean resetActive, int providers, int allowed, boolean verifiedLogin, boolean canEnable) {
}
