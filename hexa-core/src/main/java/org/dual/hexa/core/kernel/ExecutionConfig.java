package org.dual.hexa.core.kernel;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Attiva i metodi {@code @Async} (listener e watcher in background) e {@code @Scheduled} (controlli periodici) sul
 * TaskExecutor/TaskScheduler di default di Spring Boot. Sta nel core perche' ne dipende il core stesso (scadenza dei
 * token, gestione degli errori dei {@code @Async void}) e una webapp che sostituisca {@code app} non deve ricordarsene.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class ExecutionConfig {
}
