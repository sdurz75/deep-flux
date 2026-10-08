package org.dual.hexa.oauth2.login.domain;

import java.time.Instant;

/** Una voce della lista degli ammessi, per la UI: salvata ({@code id}) o d'ambiente ({@code fromEnv}, {@code id} nullo, in sola lettura). */
public record AllowedEntry(Long id, AllowKind kind, String value, boolean fromEnv, boolean bound, Instant lastLoginAt) {
}
