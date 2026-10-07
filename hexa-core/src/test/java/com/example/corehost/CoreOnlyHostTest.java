package com.example.corehost;

import static org.assertj.core.api.Assertions.assertThat;

import org.hexa.core.events.port.in.ISystemEvents;
import org.hexa.core.events.domain.CoreEventSource;
import org.hexa.core.tokens.port.in.IApiTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Prova che hexa-core si autoconfigura in un host con un package radice qualunque (qui {@code com.example.corehost}): componenti dei
 * sottosistemi, entity/repository JPA, migrazioni Flyway (default {@code classpath:db/migration}, sottocartella {@code core}) e nessuna AI
 * (hexa-ai non e' nel classpath di questo modulo). Config: solo {@code core.yml} da importare a mano, come per un host vero.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml",
        "app.tokens.expiry-check-enabled=false"})
class CoreOnlyHostTest {

    @Autowired ApplicationContext context;
    @Autowired ISystemEvents systemEvents;
    @Autowired IApiTokens apiTokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void coreComponentsEntitiesAndMigrationsComeFromTheAutoConfiguration() {
        systemEvents.warn(CoreEventSource.INTERNAL, "probe", null, "ok");

        assertThat(jdbc.queryForObject("select count(*) from system_event where operation = 'probe'", Integer.class)).isEqualTo(1);
        assertThat(apiTokens.list()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isEqualTo(1);
    }

    @Test
    void noAiLeaksIntoACoreOnlyHost() {
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase().contains("chatservice"));
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('chat_message','vector_store')",
                Integer.class)).isZero();
    }
}
