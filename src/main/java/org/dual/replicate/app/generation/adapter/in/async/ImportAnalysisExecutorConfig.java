package org.dual.replicate.app.generation.adapter.in.async;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Executor delle analisi di contenuto: pochi thread, coda ampia (un'importazione di 20 immagini non deve aprire 20 chiamate al modello insieme). */
@Configuration
class ImportAnalysisExecutorConfig {

    static final String EXECUTOR = "importAnalysisExecutor";

    @Bean(EXECUTOR)
    Executor importAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("import-analysis-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        return executor;
    }
}
