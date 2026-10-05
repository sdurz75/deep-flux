package org.dual.replicate.app.training.adapter.in.async;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor delle didascalie automatiche: pochi thread (ognuna e' una chiamata a pagamento al modello di visione) e coda ampia, perche' un caricamento di
 * 25 immagini non deve aprire 25 chiamate insieme. Con la coda piena un lavoro in piu' si SCARTA invece di far fallire la richiesta di chi lo ha avviato
 * (un caricamento gia' salvato): l'immagine resta in sospeso e lo sweep di recupero la riprende.
 */
@Configuration
class CaptionExecutorConfig {

    static final String EXECUTOR = "trainingCaptionExecutor";

    @Bean(EXECUTOR)
    Executor trainingCaptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("training-caption-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        return executor;
    }
}
