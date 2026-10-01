package org.dual.replicate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EnableAsync attiva i metodi @Async di ChatGenerationWatcher (poll
 * in background dell'esito di una generazione avviata da /deep-chat,
 * vedi CLAUDE.md punto 3 dello Scopo) sul TaskExecutor di default
 * auto-configurato da Spring Boot: nessun executor custom necessario per
 * il basso volume di questa app single-user. EnableScheduling attiva lo sweep
 * di GenerationRecoveryService (bean condizionato a app.recovery.enabled).
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
