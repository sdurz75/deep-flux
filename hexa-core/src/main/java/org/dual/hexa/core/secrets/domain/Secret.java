package org.dual.hexa.core.secrets.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un segreto con nome e TIPO (token API di un servizio, password, segreto di client OAuth2...), vedi {@code ISecrets}. Il valore e' SEMPRE cifrato
 * ({@code valueEncrypted}); {@code hint} sono gli ultimi caratteri in chiaro per riconoscerlo. {@code toString} non contiene mai il segreto, neanche
 * cifrato.
 */
@Entity
@Table(name = "secret")
public class Secret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nome del tipo ({@code ISecretTypeCatalog}): stringa, cosi' il core non conosce i servizi dell'app ne' dei moduli. */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 60)
    private String name;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, length = 1024)
    private byte[] valueEncrypted;

    @Column(nullable = false, length = 8)
    private String hint;

    private LocalDate expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Secret() {
        // richiesto da JPA
    }

    public Secret(String type, String name, byte[] valueEncrypted, String hint, LocalDate expiresAt, Instant now) {
        this.type = type;
        this.name = name;
        this.valueEncrypted = valueEncrypted;
        this.hint = hint;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, LocalDate expiresAt, Instant now) {
        this.name = name;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    public void replaceValue(byte[] valueEncrypted, String hint, Instant now) {
        this.valueEncrypted = valueEncrypted;
        this.hint = hint;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public byte[] getValueEncrypted() {
        return valueEncrypted;
    }

    public String getHint() {
        return hint;
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
        return "Secret[id=" + id + ", type=" + type + ", name=" + name + "]";
    }
}
