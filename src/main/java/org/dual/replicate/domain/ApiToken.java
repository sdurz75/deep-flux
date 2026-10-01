package org.dual.replicate.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Token API con nome (CivitAI/HuggingFace), vedi V23 e {@code ApiTokenService}. Il token e' SEMPRE cifrato
 * ({@code tokenEncrypted}); {@code hint} sono gli ultimi caratteri in chiaro per riconoscerlo. {@code toString} non
 * contiene mai il segreto, neanche cifrato.
 */
@Entity
@Table(name = "api_token")
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiTokenProvider provider;

    @Column(nullable = false, length = 60)
    private String name;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, length = 1024)
    private byte[] tokenEncrypted;

    @Column(nullable = false, length = 8)
    private String tokenHint;

    private LocalDate expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ApiToken() {
        // richiesto da JPA
    }

    public ApiToken(ApiTokenProvider provider, String name, byte[] tokenEncrypted, String tokenHint, LocalDate expiresAt, Instant now) {
        this.provider = provider;
        this.name = name;
        this.tokenEncrypted = tokenEncrypted;
        this.tokenHint = tokenHint;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, LocalDate expiresAt, Instant now) {
        this.name = name;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    public void replaceToken(byte[] tokenEncrypted, String tokenHint, Instant now) {
        this.tokenEncrypted = tokenEncrypted;
        this.tokenHint = tokenHint;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public ApiTokenProvider getProvider() {
        return provider;
    }

    public String getName() {
        return name;
    }

    public byte[] getTokenEncrypted() {
        return tokenEncrypted;
    }

    public String getTokenHint() {
        return tokenHint;
    }

    public LocalDate getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "ApiToken[id=" + id + ", provider=" + provider + ", name=" + name + "]";
    }
}
