package org.dual.replicate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * EnableAsync attiva i metodi @Async di DeepChatGenerationWatcher (poll
 * in background dell'esito di una generazione avviata da /deep-chat,
 * vedi CLAUDE.md punto 3 dello Scopo) sul TaskExecutor di default
 * auto-configurato da Spring Boot: nessun executor custom necessario per
 * il basso volume di questa app single-user.
 */
@SpringBootApplication
@EnableAsync
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
