package org.dual.hexa.core.lock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Il blocco con PIN: una sola riga ({@code id = 1}). Del PIN si conserva solo l'hash PBKDF2 con il suo sale e il numero di iterazioni usate (cosi' si
 * puo' alzare il costo senza invalidare i PIN esistenti). {@code toString} non contiene mai hash ne' sale.
 */
@Entity
@Table(name = "app_lock")
public class AppLock {

    public static final long SINGLE_ID = 1L;

    @Id
    private Long id = SINGLE_ID;

    @Column(nullable = false, length = 100)
    private String pinHash;

    @Column(nullable = false, length = 40)
    private String pinSalt;

    @Column(nullable = false)
    private int iterations;

    @Column(nullable = false)
    private int idleTimeoutSeconds;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AppLock() {
        // richiesto da JPA
    }

    public AppLock(String pinHash, String pinSalt, int iterations, int idleTimeoutSeconds, Instant now) {
        this.pinHash = pinHash;
        this.pinSalt = pinSalt;
        this.iterations = iterations;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changePin(String pinHash, String pinSalt, int iterations, Instant now) {
        this.pinHash = pinHash;
        this.pinSalt = pinSalt;
        this.iterations = iterations;
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public void changeIdleTimeout(int seconds, Instant now) {
        this.idleTimeoutSeconds = seconds;
        this.updatedAt = now;
    }

    public void recordFailure(Instant lockedUntil, Instant now) {
        this.failedAttempts++;
        this.lockedUntil = lockedUntil;
        this.updatedAt = now;
    }

    public void recordSuccess(Instant now) {
        if (failedAttempts != 0 || lockedUntil != null) {
            this.failedAttempts = 0;
            this.lockedUntil = null;
            this.updatedAt = now;
        }
    }

    public String getPinHash() {
        return pinHash;
    }

    public String getPinSalt() {
        return pinSalt;
    }

    public int getIterations() {
        return iterations;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    @Override
    public String toString() {
        return "AppLock{idleTimeoutSeconds=" + idleTimeoutSeconds + ", failedAttempts=" + failedAttempts + "}";
    }
}
