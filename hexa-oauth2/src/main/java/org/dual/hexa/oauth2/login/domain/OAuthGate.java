package org.dual.hexa.oauth2.login.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Il cancello di accesso: UNA riga ({@code id = 1}). {@code enabled} e' la scelta fatta dalla UI; {@code verifiedLoginAt} l'ultimo accesso di prova riuscito
 * di un utente ammesso, senza il quale non si puo' accendere (altrimenti un provider sbagliato chiuderebbe fuori tutti).
 */
@Entity
@Table(name = "oauth2_gate")
public class OAuthGate {

    public static final long SINGLE_ID = 1L;

    @Id
    private Long id = SINGLE_ID;

    @Column(nullable = false)
    private boolean enabled;

    private Instant verifiedLoginAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected OAuthGate() {
        // richiesto da JPA
    }

    public OAuthGate(Instant now) {
        this.updatedAt = now;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getVerifiedLoginAt() {
        return verifiedLoginAt;
    }

    public void setEnabled(boolean enabled, Instant now) {
        this.enabled = enabled;
        this.updatedAt = now;
    }

    public void verified(Instant now) {
        this.verifiedLoginAt = now;
        this.updatedAt = now;
    }
}
