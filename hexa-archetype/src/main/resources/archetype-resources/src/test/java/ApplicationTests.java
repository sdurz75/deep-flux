package ${package};

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Il contesto completo parte: autoconfigurazione di hexa, schema Flyway (core, ai, app) e validazione JPA su un PostgreSQL vero (Testcontainers). */
@SpringBootTest
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
