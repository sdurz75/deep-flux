package org.hexa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto d'ingresso: nessuna configurazione qui. {@code @EnableAsync}/{@code @EnableScheduling} sono nel core
 * ({@code core.kernel.ExecutionConfig}), cosi' sostituire {@code app} non li fa perdere.
 *
 * <p>Lo stesso jar e' anche lo strumento di backup: {@code java -jar app.jar export <file>} / {@code import <file>} avviano il profilo
 * {@code backup} (senza web ne' lavori in background, vedi {@code application-backup.yml}), dove un {@code ApplicationRunner} del core
 * ({@code core.backup}) esegue il comando ed esce. Senza un sottocomando e' il server di sempre.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(Application.class);
        if (args.length > 0 && (args[0].equals("export") || args[0].equals("import"))) {
            application.setAdditionalProfiles("backup");
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }
}
