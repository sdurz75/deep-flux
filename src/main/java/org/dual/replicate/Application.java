package org.dual.replicate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto d'ingresso: nessuna configurazione qui. {@code @EnableAsync}/{@code @EnableScheduling} sono nel core
 * ({@code core.kernel.ExecutionConfig}), cosi' sostituire {@code app} non li fa perdere.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
