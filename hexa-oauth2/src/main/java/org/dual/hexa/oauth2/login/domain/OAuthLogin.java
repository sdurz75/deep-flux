package org.dual.hexa.oauth2.login.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * L'ultimo accesso riuscito di un'email ammessa: lega l'identita' ({@code issuer}+{@code subject}) al primo accesso (un'altra identita' con la stessa email
 * e' respinta se l'email e' nella lista esatta) e prova che almeno un accesso e' riuscito (precondizione per accendere il cancello). Si svuota quando
 * cambiano i provider.
 */
@Entity
@Table(name = "oauth2_login")
public class OAuthLogin {

    @Id
    @Column(length = 255)
    private String email;

    @Column(nullable = false, length = 300)
    private String issuer;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false)
    private Instant lastLoginAt;

    protected OAuthLogin() {
        // richiesto da JPA
    }

    public OAuthLogin(String email, String issuer, String subject, Instant now) {
        this.email = email;
        this.issuer = issuer;
        this.subject = subject;
        this.lastLoginAt = now;
    }

    public boolean matches(String otherIssuer, String otherSubject) {
        return issuer.equals(otherIssuer) && subject.equals(otherSubject);
    }

    public void loggedIn(String newIssuer, String newSubject, Instant now) {
        this.issuer = newIssuer;
        this.subject = newSubject;
        this.lastLoginAt = now;
    }

    public String getEmail() {
        return email;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
