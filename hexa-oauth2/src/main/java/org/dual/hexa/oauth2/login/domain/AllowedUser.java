package org.dual.hexa.oauth2.login.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Una voce salvata della lista degli ammessi. Per un'email esatta, al primo accesso si legano {@code issuer} e {@code subject} del provider: da quel momento
 * quell'email vale solo per quell'identita' (un account ricreato con la stessa email o un provider diverso non entra).
 */
@Entity
@Table(name = "oauth2_allowed_user")
public class AllowedUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AllowKind kind;

    @Column(nullable = false, length = 255)
    private String value;

    @Column(length = 255)
    private String issuer;

    @Column(length = 255)
    private String subject;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastLoginAt;

    protected AllowedUser() {
        // richiesto da JPA
    }

    public AllowedUser(AllowKind kind, String value, Instant now) {
        this.kind = kind;
        this.value = value;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public AllowKind getKind() {
        return kind;
    }

    public String getValue() {
        return value;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public boolean isBound() {
        return subject != null;
    }

    /** Lega l'identita' del provider (solo la prima volta, solo per le email). */
    public void bind(String issuer, String subject) {
        this.issuer = issuer;
        this.subject = subject;
    }

    public boolean matchesIdentity(String issuer, String subject) {
        return this.issuer != null && this.issuer.equals(issuer) && this.subject != null && this.subject.equals(subject);
    }

    public void loggedIn(Instant now) {
        this.lastLoginAt = now;
    }
}
