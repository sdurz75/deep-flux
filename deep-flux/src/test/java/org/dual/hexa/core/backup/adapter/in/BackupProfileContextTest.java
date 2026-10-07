package org.dual.hexa.core.backup.adapter.in;

import java.util.ArrayList;
import java.util.List;

import org.dual.hexa.core.backup.port.in.IBackupExport;
import org.dual.hexa.core.backup.port.in.IBackupImport;
import org.dual.hexa.core.backup.port.out.IDatabaseRestore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il contesto del profilo {@code backup} (quello dei comandi {@code export}/{@code import}) parte SENZA le credenziali dei servizi
 * remoti dell'app (token Replicate e OpenRouter vuoti), senza web e senza nessun lavoro in background: un comando di backup non deve
 * poter chiamare servizi a pagamento ne' avviare recuperi o indicizzazioni. La verifica e' generica (cerca i metodi {@code @Scheduled} e i
 * listener di {@code ApplicationReadyEvent} in OGNI bean), cosi' un job aggiunto in futuro all'app fa fallire questo test invece di
 * partire di nascosto durante un backup. Gira col SOLO profilo {@code backup} (non anche "test", che spegne gli stessi job per conto suo): cosi' e' davvero
 * {@code application-backup.yml} a garantirlo, come nel jar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.ai.openai.api-key=", "replicate.api-token=", "openrouter.management-key=", "searxng.password=",
                // Un contesto in piu' con il pool Hikari di default (10 connessioni tenute aperte) manda il container dei test oltre
                // max_connections (100) e fa fallire, a caso, altri test ("too many clients"): questo ne ha bisogno di una o due.
                "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"})
@ActiveProfiles("backup")
class BackupProfileContextTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private IDatabaseRestore restore;
    @Autowired
    private IBackupExport exporter;
    @Autowired
    private IBackupImport importer;
    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void startsWithoutRemoteServiceCredentialsAndWithoutAWebServer() {
        assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
    }

    @Test
    void theBackupBeansAreWiredWithOnlyTheBackupProfile() {
        assertThat(environment.getActiveProfiles()).containsExactly("backup");
        assertThat(exporter).isNotNull();
        assertThat(importer).isNotNull();
        // Le locations di Flyway arrivano da spring.flyway.locations (una stringa separata da virgole): se non si dividessero, nessuna versione sarebbe "nota".
        assertThat(restore.knowsSchemaVersion("2026.10.01.1200")).as("baseline del core").isTrue();
        assertThat(restore.knowsSchemaVersion("2026.10.01.1220")).as("baseline dell'app").isTrue();
        assertThat(restore.knowsSchemaVersion("1999.01.01.0000")).isFalse();
    }

    @Test
    void noBeanSchedulesWorkOrReactsToApplicationReady() {
        List<String> offenders = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type != null) {
                collectBackgroundWork(name, type, offenders);
            }
        }
        assertThat(offenders).as("lavori in background attivi nel profilo backup").isEmpty();
    }

    /** Il rilevatore non e' vacuo: riconosce i job veri (qui il controllo di scadenza dei token, che ha entrambe le forme). */
    @Test
    void theDetectorRecognisesARealBackgroundJob() {
        List<String> offenders = new ArrayList<>();

        collectBackgroundWork("tokenExpiryScheduler", org.dual.hexa.core.tokens.adapter.in.scheduling.TokenExpiryScheduler.class, offenders);

        assertThat(offenders).anyMatch(o -> o.contains("(@Scheduled)")).anyMatch(o -> o.contains("(ApplicationReadyEvent)"));
    }

    private static void collectBackgroundWork(String name, Class<?> type, List<String> offenders) {
        ReflectionUtils.doWithMethods(ClassUtils.getUserClass(type), method -> {
            if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class) || AnnotatedElementUtils.hasAnnotation(method, Schedules.class)) {
                offenders.add(name + "#" + method.getName() + " (@Scheduled)");
            }
            EventListener listener = AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class);
            if (listener != null && (List.of(listener.classes()).contains(ApplicationReadyEvent.class)
                    || List.of(method.getParameterTypes()).contains(ApplicationReadyEvent.class))) {
                offenders.add(name + "#" + method.getName() + " (ApplicationReadyEvent)");
            }
        });
    }
}
