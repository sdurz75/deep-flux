package org.dual.hexa.core.lock.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.lock.domain.AppLock;
import org.dual.hexa.core.lock.domain.LockException;
import org.dual.hexa.core.lock.port.in.ILock;
import org.dual.hexa.core.lock.port.out.ILockStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il PIN si conserva solo come hash PBKDF2-HMAC-SHA256 con sale casuale (confronto a tempo costante). Un PIN di poche cifre si forza in fretta, quindi ogni
 * errore dal quarto in poi blocca i tentativi per un tempo che raddoppia (5 s, 10 s, ... fino a 15 minuti), persistito: riavviare l'app non lo azzera. Lo stato
 * {@code enabled}/timeout e' tenuto in memoria (l'interceptor lo legge a OGNI richiesta) e rinfrescato a ogni modifica: l'app e' a istanza singola.
 */
@Service
class LockService implements ILock {

    static final int ITERATIONS = 600_000;
    static final int FREE_ATTEMPTS = 3;
    static final Duration FIRST_DELAY = Duration.ofSeconds(5);
    static final Duration MAX_DELAY = Duration.ofMinutes(15);
    private static final Pattern PIN_FORMAT = Pattern.compile("\\d{4,8}");

    private record Snapshot(boolean enabled, int idleTimeoutSeconds) {
    }

    private final ILockStore store;
    private final Messages messages;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile Snapshot snapshot;

    @Autowired
    LockService(ILockStore store, Messages messages) {
        this(store, messages, Clock.systemUTC());
    }

    LockService(ILockStore store, Messages messages, Clock clock) {
        this.store = store;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public boolean isEnabled() {
        return snapshot().enabled();
    }

    @Override
    public int idleTimeoutSeconds() {
        return snapshot().idleTimeoutSeconds();
    }

    @Override
    @Transactional
    public void enable(String pin, int idleTimeoutSeconds) {
        requireValidPin(pin);
        requireValidTimeout(idleTimeoutSeconds);
        if (store.find().isPresent()) {
            throw new LockException(messages.get("lock.error.alreadyEnabled"));
        }
        byte[] salt = newSalt();
        store.save(new AppLock(hash(pin, salt, ITERATIONS), encode(salt), ITERATIONS, idleTimeoutSeconds, clock.instant()));
        snapshot = null;
    }

    @Override
    @Transactional(noRollbackFor = LockException.class)
    public void verify(String pin) {
        check(requireEnabled(), pin);
    }

    @Override
    @Transactional(noRollbackFor = LockException.class)
    public void changePin(String currentPin, String newPin) {
        AppLock lock = requireEnabled();
        check(lock, currentPin);
        requireValidPin(newPin);
        byte[] salt = newSalt();
        lock.changePin(hash(newPin, salt, ITERATIONS), encode(salt), ITERATIONS, clock.instant());
        store.save(lock);
    }

    @Override
    @Transactional(noRollbackFor = LockException.class)
    public void changeIdleTimeout(String currentPin, int idleTimeoutSeconds) {
        AppLock lock = requireEnabled();
        check(lock, currentPin);
        requireValidTimeout(idleTimeoutSeconds);
        lock.changeIdleTimeout(idleTimeoutSeconds, clock.instant());
        store.save(lock);
        snapshot = null;
    }

    @Override
    @Transactional(noRollbackFor = LockException.class)
    public void disable(String currentPin) {
        check(requireEnabled(), currentPin);
        store.delete();
        snapshot = null;
    }

    @Override
    @Transactional
    public void reset() {
        if (store.find().isPresent()) {
            store.delete();
        }
        snapshot = null;
    }

    @Override
    @Transactional
    public void forceSetPin(String pin) {
        requireValidPin(pin);
        byte[] salt = newSalt();
        Optional<AppLock> existing = store.find();
        if (existing.isPresent()) {
            AppLock lock = existing.get();
            lock.changePin(hash(pin, salt, ITERATIONS), encode(salt), ITERATIONS, clock.instant());
            store.save(lock);
        } else {
            store.save(new AppLock(hash(pin, salt, ITERATIONS), encode(salt), ITERATIONS, DEFAULT_TIMEOUT_SECONDS, clock.instant()));
        }
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            Optional<AppLock> lock = store.find();
            current = new Snapshot(lock.isPresent(), lock.map(AppLock::getIdleTimeoutSeconds).orElse(DEFAULT_TIMEOUT_SECONDS));
            snapshot = current;
        }
        return current;
    }

    private AppLock requireEnabled() {
        return store.find().orElseThrow(() -> new LockException(messages.get("lock.error.notEnabled")));
    }

    /** Il cancello: rispetta il rallentamento, confronta a tempo costante, aggiorna i tentativi (anche quando il confronto fallisce: noRollbackFor). */
    private void check(AppLock lock, String pin) {
        Instant now = clock.instant();
        if (lock.getLockedUntil() != null && lock.getLockedUntil().isAfter(now)) {
            throw throttled(lock.getLockedUntil(), now);
        }
        boolean ok = pin != null && PIN_FORMAT.matcher(pin).matches()
                && MessageDigest.isEqual(hash(pin, decode(lock.getPinSalt()), lock.getIterations()).getBytes(StandardCharsets.UTF_8),
                        lock.getPinHash().getBytes(StandardCharsets.UTF_8));
        if (ok) {
            lock.recordSuccess(now);
            store.save(lock);
            return;
        }
        int failures = lock.getFailedAttempts() + 1;
        Instant until = failures <= FREE_ATTEMPTS ? null : now.plus(delayFor(failures));
        lock.recordFailure(until, now);
        store.save(lock);
        throw until == null ? new LockException(messages.get("lock.error.wrongPin")) : throttled(until, now);
    }

    static Duration delayFor(int failures) {
        long factor = 1L << Math.min(failures - FREE_ATTEMPTS - 1, 20);
        Duration delay = FIRST_DELAY.multipliedBy(factor);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    private LockException throttled(Instant until, Instant now) {
        long seconds = Math.max(1, Duration.between(now, until).toSeconds() + 1);
        return new LockException(messages.get("lock.error.throttled", seconds));
    }

    private void requireValidPin(String pin) {
        if (pin == null || !PIN_FORMAT.matcher(pin).matches()) {
            throw new LockException(messages.get("lock.error.pinFormat"));
        }
    }

    private void requireValidTimeout(int seconds) {
        if (!ALLOWED_TIMEOUTS.contains(seconds)) {
            throw new LockException(messages.get("lock.error.timeout"));
        }
    }

    private byte[] newSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String text) {
        return Base64.getDecoder().decode(text);
    }

    private static String hash(String pin, byte[] salt, int iterations) {
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).getEncoded();
            return encode(derived);
        } catch (GeneralSecurityException e) {
            // Algoritmo garantito dal JDK: se manca, l'ambiente e' rotto e fallire forte e' giusto (non e' un errore dell'utente).
            throw new IllegalStateException("PBKDF2WithHmacSHA256 non disponibile", e);
        }
    }
}
