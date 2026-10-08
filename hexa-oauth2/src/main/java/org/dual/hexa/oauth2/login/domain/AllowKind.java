package org.dual.hexa.oauth2.login.domain;

/** Che cosa dice una voce della lista degli ammessi: un'email esatta o un dominio intero (la parte dopo l'ultima chiocciola, mai un suffisso). */
public enum AllowKind {
    EMAIL,
    DOMAIN
}
