package org.dual.hexa.oauth2.login.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Un provider OIDC salvato dalla UI. Il segreto del client NON e' qui: e' un token di {@code /tokens} (provider {@code OAUTH2}), cifrato dal core, scelto
 * per id in {@code secretTokenId} (colonna semplice: nessuna FK, un token cancellato si scopre al login come rifiuto di configurazione).
 */
@Entity
@Table(name = "oauth2_provider")
public class OAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, unique = true)
    private String slug;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(nullable = false, length = 300)
    private String issuerUri;

    @Column(nullable = false, length = 300)
    private String clientId;

    @Column(nullable = false)
    private Long secretTokenId;

    @Column(nullable = false)
    private Instant createdAt;

    protected OAuthProvider() {
        // richiesto da JPA
    }

    public OAuthProvider(String slug, String title, String issuerUri, String clientId, Long secretTokenId, Instant now) {
        this.slug = slug;
        this.title = title;
        this.issuerUri = issuerUri;
        this.clientId = clientId;
        this.secretTokenId = secretTokenId;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public Long getSecretTokenId() {
        return secretTokenId;
    }

    public ProviderConfig toConfig() {
        return new ProviderConfig(id, slug, title, issuerUri, clientId, secretTokenId, false);
    }
}
