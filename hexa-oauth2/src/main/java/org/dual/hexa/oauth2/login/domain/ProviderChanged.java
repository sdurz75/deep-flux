package org.dual.hexa.oauth2.login.domain;

/** Evento interno: un provider e' stato creato o cancellato, la cache delle registrazioni OIDC va svuotata. */
public record ProviderChanged() {
}
