package org.dual.hexa.app.training.adapter.in.async;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor del risultato dei training: un solo thread (sono lavori rari e brevi: un preset, un controllo) e coda piccola. Con la coda piena un lavoro in piu' si
 * SCARTA: il risultato resta incompleto sulla riga e lo sweep di recupero lo riprende.
 */
@Configuration
class TrainingResultExecutorConfig {

    static final String EXECUTOR = "trainingResultExecutor";

    @Bean(EXECUTOR)
    Executor trainingResultExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("training-result-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }
}
