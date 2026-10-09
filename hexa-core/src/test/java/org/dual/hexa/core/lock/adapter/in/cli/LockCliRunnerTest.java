package org.dual.hexa.core.lock.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.lock.domain.LockException;
import org.dual.hexa.core.lock.port.in.ILock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Argomenti e codici di uscita di {@code set-pin}, senza far uscire la JVM e senza database. */
class LockCliRunnerTest {

    private ILock lock;
    private LockCliRunner runner;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        lock = mock(ILock.class);
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages-core");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        runner = new LockCliRunner(lock, mock(ISystemEvents.class), new Messages(source), mock(ApplicationContext.class));
    }

    private int run(String... args) {
        return runner.execute(new DefaultApplicationArguments(args), new PrintStream(out), new PrintStream(err));
    }

    @Test
    void setsThePinAndExitsZero() {
        assertThat(run("set-pin", "4321")).isZero();

        verify(lock).forceSetPin("4321");
        assertThat(out.toString()).isNotBlank();
    }

    @Test
    void anInvalidPinExitsOne() {
        doThrow(new LockException("PIN non valido")).when(lock).forceSetPin(any());

        assertThat(run("set-pin", "12")).isEqualTo(1);
        assertThat(err.toString()).contains("PIN non valido");
    }

    @Test
    void wrongUsageExitsTwoWithoutTouchingTheLock() {
        assertThat(run("set-pin")).isEqualTo(2);
        assertThat(run("set-pin", "1234", "5678")).isEqualTo(2);
        assertThat(run("set-pin", "1234", "--x")).isEqualTo(2);

        verifyNoInteractions(lock);
    }
}
