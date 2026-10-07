package com.example.aihost;

import static org.assertj.core.api.Assertions.assertThat;

import org.hexa.core.chat.port.in.IChatConversations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;

/**
 * Prova che hexa-core + hexa-ai si autoconfigurano in un host fuori da {@code org.hexa}: componenti e repository della chat, migrazioni
 * (core, ai: sottocartelle di {@code classpath:db/migration}) e bundle {@code messages-ai} aggiunto da solo. La ricerca semantica resta spenta
 * (nessun embedding locale). Config dell'host: i due YAML da importare, l'URL SearXNG e i testi dei prompt.
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml,classpath:ai.yml,classpath:host-prompts.properties",
        "spring.ai.model.embedding=none",
        "app.search.enabled=false",
        "app.tokens.expiry-check-enabled=false",
        "searxng.base-url=http://localhost:1/"})
class AiHostTest {

    @Autowired IChatConversations conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageSource messages;

    @Test
    void chatEntitiesRepositoriesAndMigrationsComeFromTheAutoConfiguration() {
        Long id = conversations.create().getId();

        assertThat(jdbc.queryForObject("select count(*) from chat_conversation where id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isEqualTo(2);
    }

    @Test
    void theAiBundleIsRegisteredWithoutHostConfiguration() {
        assertThat(messages.getMessage("search.title", null, Locale.ITALIAN)).doesNotContain("??");
    }
}
