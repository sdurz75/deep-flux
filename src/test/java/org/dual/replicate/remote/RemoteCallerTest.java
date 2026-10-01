package org.dual.replicate.remote;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.dual.replicate.core.events.domain.CoreEventSource;
import org.dual.replicate.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteCallerTest {

    private static final RetryPolicy FAST = RetryPolicy.of(2, Duration.ZERO);

    private static RemoteServiceException error(Kind kind, String message, Throwable cause) {
        return new RemoteServiceException(CoreEventSource.INTERNAL, kind, message, cause);
    }

    private final RemoteCaller caller = RemoteCaller.builder(e -> error(Kind.PERMANENT, "tradotta", e))
            .retry(FAST).passThrough(NoSuchFileException.class).build();

    @Test
    void returnsTheResultWithoutRetrying() {
        assertThat(caller.call("op", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void aTransientFailureIsRetriedUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = caller.call("op", () -> {
            if (calls.incrementAndGet() < 3) {
                throw error(Kind.TRANSIENT, "503", null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void retriesAreBoundedThenTheLastTransientErrorSurfaces() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> caller.call("op", () -> {
            calls.incrementAndGet();
            throw error(Kind.TRANSIENT, "503", null);
        })).isInstanceOfSatisfying(RemoteServiceException.class, e -> assertThat(e.isTransient()).isTrue());

        assertThat(calls).hasValue(3); // 1 tentativo + 2 ritentativi
    }

    @Test
    void nonTransientKindsAreNeverRetried() {
        for (Kind kind : new Kind[] {Kind.PERMANENT, Kind.CONFIGURATION, Kind.REJECTED}) {
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> caller.call("op", () -> {
                calls.incrementAndGet();
                throw error(kind, "no", null);
            })).isInstanceOf(RemoteServiceException.class);
            assertThat(calls).as(kind.name()).hasValue(1);
        }
    }

    @Test
    void aPerCallPolicyOverridesTheDefault() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> caller.call("op", RetryPolicy.NONE, () -> {
            calls.incrementAndGet();
            throw error(Kind.TRANSIENT, "503", null);
        })).isInstanceOf(RemoteServiceException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void foreignExceptionsAreTranslatedWithTheirCause() {
        IOException io = new IOException("boom");

        assertThatThrownBy(() -> caller.call("op", () -> {
            throw io;
        })).isInstanceOfSatisfying(RemoteServiceException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("tradotta");
            assertThat(e.getCause()).isSameAs(io);
        });
    }

    @Test
    void anAlreadyTranslatedExceptionIsNotTranslatedAgain() {
        RemoteServiceException original = error(Kind.PERMANENT, "originale", null);

        assertThatThrownBy(() -> caller.call("op", () -> {
            throw original;
        })).isSameAs(original);
    }

    /** RestClient incapsula in ResourceAccessException cio' che esce da un exchange: la classificazione non si perde. */
    @Test
    void aClassifiedErrorWrappedByTheHttpClientKeepsItsKindAndIsNotRetried() {
        RemoteServiceException permanent = error(Kind.PERMANENT, "403", null);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> caller.call("op", () -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.ResourceAccessException("I/O error", new IOException(permanent));
        })).isSameAs(permanent);

        assertThat(calls).hasValue(1);
    }

    @Test
    void passThroughExceptionsAreRethrownAsIsEvenWhenWrapped() {
        NoSuchFileException missing = new NoSuchFileException("x");
        RuntimeException wrapped = new RuntimeException("incapsulata da RestClient", missing);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> caller.call("op", () -> {
            calls.incrementAndGet();
            throw wrapped;
        })).isSameAs(missing);

        assertThat(calls).hasValue(1);
    }

    @Test
    void anInterruptStopsTheRetriesAndKeepsTheInterruptFlag() {
        RemoteCaller slow = RemoteCaller.builder(e -> error(Kind.PERMANENT, "t", e))
                .retry(RetryPolicy.of(2, Duration.ofSeconds(30))).build();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> slow.call("op", () -> {
                throw error(Kind.TRANSIENT, "503", null);
            })).isInstanceOf(RemoteServiceException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
