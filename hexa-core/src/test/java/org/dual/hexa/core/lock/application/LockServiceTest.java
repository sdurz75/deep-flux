package org.dual.hexa.core.lock.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.lock.domain.AppLock;
import org.dual.hexa.core.lock.domain.LockException;
import org.dual.hexa.core.lock.port.out.ILockStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Le regole del PIN senza Spring ne' database: formato, hash, rallentamento dei tentativi, PIN attuale per ogni modifica. */
class LockServiceTest {

    /** Orologio manovrabile: il rallentamento si prova senza aspettare. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class MemoryStore implements ILockStore {
        AppLock lock;

        @Override
        public Optional<AppLock> find() {
            return Optional.ofNullable(lock);
        }

        @Override
        public AppLock save(AppLock saved) {
            lock = saved;
            return saved;
        }

        @Override
        public void delete() {
            lock = null;
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final MutableClock clock = new MutableClock();
    private LockService service;

    @BeforeEach
    void setUp() {
        Messages messages = mock(Messages.class);
        when(messages.get(any(String.class), any(Object[].class))).thenAnswer(call -> call.getArgument(0));
        service = new LockService(store, messages, clock);
    }

    @Test
    void withoutAPinTheLockIsOffAndTheTimeoutIsTheDefault() {
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.idleTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void thePinIsStoredOnlyAsAHashWithItsSalt() {
        service.enable("1234", 900);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.idleTimeoutSeconds()).isEqualTo(900);
        assertThat(store.lock.getPinHash()).isNotEqualTo("1234").hasSizeGreaterThan(30);
        assertThat(store.lock.getPinSalt()).isNotBlank();
        assertThat(store.lock.toString()).doesNotContain(store.lock.getPinHash()).doesNotContain(store.lock.getPinSalt());
        service.verify("1234");
    }

    @Test
    void aMalformedPinOrTimeoutIsRefused() {
        assertThatThrownBy(() -> service.enable("12", 300)).isInstanceOf(LockException.class).hasMessage("lock.error.pinFormat");
        assertThatThrownBy(() -> service.enable("12345678901", 300)).hasMessage("lock.error.pinFormat");
        assertThatThrownBy(() -> service.enable("12a4", 300)).hasMessage("lock.error.pinFormat");
        assertThatThrownBy(() -> service.enable("1234", 7)).hasMessage("lock.error.timeout");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void theLockCannotBeEnabledTwice() {
        service.enable("1234", 300);

        assertThatThrownBy(() -> service.enable("5678", 300)).hasMessage("lock.error.alreadyEnabled");
    }

    @Test
    void theFirstThreeWrongPinsAreFreeThenAttemptsAreSlowedAndEvenTheRightPinWaits() {
        service.enable("1234", 300);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.verify("0000")).hasMessage("lock.error.wrongPin");
        }
        assertThatThrownBy(() -> service.verify("0000")).hasMessage("lock.error.throttled");
        assertThatThrownBy(() -> service.verify("1234")).as("il PIN giusto durante il rallentamento").hasMessage("lock.error.throttled");

        clock.advance(Duration.ofSeconds(6));
        service.verify("1234");
        assertThat(store.lock.getFailedAttempts()).as("un successo azzera i tentativi").isZero();
    }

    @Test
    void theDelayDoublesUpToACap() {
        assertThat(LockService.delayFor(4)).isEqualTo(Duration.ofSeconds(5));
        assertThat(LockService.delayFor(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(LockService.delayFor(6)).isEqualTo(Duration.ofSeconds(20));
        assertThat(LockService.delayFor(40)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void everyChangeNeedsTheCurrentPin() {
        service.enable("1234", 300);

        assertThatThrownBy(() -> service.changePin("9999", "5678")).hasMessage("lock.error.wrongPin");
        assertThatThrownBy(() -> service.changeIdleTimeout("9999", 900)).hasMessage("lock.error.wrongPin");
        assertThatThrownBy(() -> service.disable("9999")).hasMessage("lock.error.wrongPin");
        assertThat(service.isEnabled()).isTrue();

        service.changePin("1234", "5678");
        service.verify("5678");
        service.changeIdleTimeout("5678", 3600);
        assertThat(service.idleTimeoutSeconds()).isEqualTo(3600);
        service.disable("5678");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void resetTurnsTheLockOffWithoutAPin() {
        service.enable("1234", 300);

        service.reset();

        assertThat(service.isEnabled()).isFalse();
        assertThat(store.lock).isNull();
    }
}
