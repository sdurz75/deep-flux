package com.example.aihost;

import static org.assertj.core.api.Assertions.assertThat;

import org.dual.hexa.ai.llm.port.in.IImageCaptioner;
import org.dual.hexa.ai.llm.port.in.IImageDescriber;
import org.dual.hexa.ai.llm.port.in.IPromptEnhancer;
import org.dual.hexa.ai.chat.port.in.IChatConversations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;

/**
 * Prova che hexa-core + hexa-ai si autoconfigurano in un host fuori da {@code org.dual.hexa}: componenti e repository della chat, migrazioni
 * (core, ai: sottocartelle di {@code classpath:db/migration}) e bundle {@code messages-ai} aggiunto da solo. La ricerca semantica resta spenta
 * (nessun embedding locale). Config dell'host: i due YAML da importare e i testi dei prompt dei tool; niente URL SearXNG ne' guide di visione (opzionali).
 */
@SpringBootTest(properties = {
        "spring.config.import=classpath:core.yml,classpath:ai.yml,classpath:host-prompts.properties",
        "spring.ai.model.embedding=none",
        "app.search.enabled=false",
        "app.tokens.expiry-check-enabled=false"})
class AiHostTest {

    @Autowired IChatConversations conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageSource messages;
    @Autowired ApplicationContext context;

    @Test
    void chatEntitiesRepositoriesAndMigrationsComeFromTheAutoConfiguration() {
        Long id = conversations.create().getId();

        assertThat(jdbc.queryForObject("select count(*) from chat_conversation where id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class)).isEqualTo(4);
    }

    /** Le guide di visione sono del dominio dell'host: senza, i servizi non nascono e l'avvio non fallisce. */
    @Test
    void visionServicesExistOnlyWhenTheHostDefinesTheirGuides() {
        assertThat(context.getBeanNamesForType(IImageDescriber.class)).isEmpty();
        assertThat(context.getBeanNamesForType(IImageCaptioner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(IPromptEnhancer.class)).isEmpty();
    }

    @Test
    void theAiBundleIsRegisteredWithoutHostConfiguration() {
        assertThat(messages.getMessage("search.title", null, Locale.ITALIAN)).doesNotContain("??");
    }
}
