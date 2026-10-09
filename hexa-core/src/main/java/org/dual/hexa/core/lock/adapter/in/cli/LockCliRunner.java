package org.dual.hexa.core.lock.adapter.in.cli;

import java.io.PrintStream;
import java.util.List;

import org.dual.hexa.core.events.domain.CoreEventSource;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.lock.domain.LockException;
import org.dual.hexa.core.lock.port.in.ILock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Il comando {@code set-pin <pin>} del jar: imposta il PIN (4-8 cifre) senza chiedere quello attuale e ESCE, senza avviare il server. Recupero di un PIN
 * dimenticato quando non si vuole spegnere il blocco ({@code HX_LOCK_RESET}). {@code Application.main} attiva il profilo {@code backup} (senza web ne'
 * lavori in background) e arriva qui; esito 0 = ok, 1 = errore, 2 = uso errato. Chi puo' lanciare il jar legge gia' il database: non indebolisce il blocco.
 * Il PIN resta nella cronologia della shell e in {@code ps}: e' un attrezzo da amministratore, da cambiare poi dalla pagina {@code /security}.
 */
@Component
@Profile("backup")
class LockCliRunner implements ApplicationRunner {

    static final String SET_PIN = "set-pin";

    private static final Logger log = LoggerFactory.getLogger(LockCliRunner.class);

    private final ILock lock;
    private final ISystemEvents systemEvents;
    private final Messages messages;
    private final ApplicationContext context;

    LockCliRunner(ILock lock, ISystemEvents systemEvents, Messages messages, ApplicationContext context) {
        this.lock = lock;
        this.systemEvents = systemEvents;
        this.messages = messages;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> words = args.getNonOptionArgs();
        if (words.isEmpty() || !SET_PIN.equals(words.get(0))) {
            return;
        }
        int code = execute(args, System.out, System.err);
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** Esegue il comando e ritorna il codice di uscita; separato da {@link #run} per poterlo provare senza far uscire la JVM. */
    int execute(ApplicationArguments args, PrintStream out, PrintStream err) {
        List<String> words = args.getNonOptionArgs();
        if (words.size() != 2 || !args.getOptionNames().isEmpty()) {
            err.println(messages.get("lock.cli.usage"));
            return 2;
        }
        try {
            lock.forceSetPin(words.get(1));
            systemEvents.warn(CoreEventSource.INTERNAL, "lockSetPin", null, messages.get("lock.event.pinSet"));
            out.println(messages.get("lock.cli.done"));
            return 0;
        } catch (LockException e) {
            err.println(messages.get("lock.cli.failed", e.getMessage()));
            return 1;
        } catch (RuntimeException e) {
            log.error("Comando {} fallito", SET_PIN, e);
            err.println(messages.get("lock.cli.failed", String.valueOf(e.getMessage())));
            return 1;
        }
    }
}
