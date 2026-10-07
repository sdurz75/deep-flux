package org.hexa.app.training.adapter.in.async;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor dei caricamenti a mano dei pesi su HuggingFace: due thread (ognuno scarica e carica centinaia di MB, e non devono intasare quello del risultato) e
 * una coda corta. Con la coda piena il lavoro in piu' e' RIFIUTATO (non scartato in silenzio): chi l'ha chiesto lo vede, e il bottone non resta bloccato.
 */
@Configuration
class TrainingHfUploadExecutorConfig {

    static final String EXECUTOR = "trainingHfUploadExecutor";

    @Bean(EXECUTOR)
    Executor trainingHfUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("training-hf-upload-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        return executor;
    }
}
