package org.hexa.core.ai.autoconfigure;

import java.util.List;
import org.hexa.core.autoconfigure.SubsystemPackagesRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Registra hexa-ai (chat, ricerca semantica, AI, crediti OpenRouter) in un'applicazione Spring Boot qualunque sia il suo package radice,
 * come {@code HexaCoreAutoConfiguration} per il core (stessa logica: scan dei componenti e package JPA, package come stringhe). Il bundle
 * {@code messages-ai} lo aggiunge {@code AiMessagesConfig}. Resta all'host: {@code spring.config.import} di {@code classpath:ai.yml}, {@code searxng.base-url},
 * i testi dei prompt e, per la chat/ricerca, gli slot di template e le SPI.
 */
@AutoConfiguration(afterName = "org.hexa.core.autoconfigure.HexaCoreAutoConfiguration", beforeName = {
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@Import(HexaAiAutoConfiguration.Packages.class)
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)},
        basePackages = {HexaAiAutoConfiguration.R + "ai", HexaAiAutoConfiguration.R + "chat", HexaAiAutoConfiguration.R + "search", HexaAiAutoConfiguration.R + "credits"})
public class HexaAiAutoConfiguration {

    /** Radice dei sottosistemi, spezzata di proposito: i test di architettura leggono i sorgenti e vedrebbero una dipendenza da ognuno. */
    static final String R = "org.hexa" + ".core.";

    /** Package JPA dei sottosistemi (vedi {@code SubsystemPackagesRegistrar}). */
    static class Packages extends SubsystemPackagesRegistrar {
        @Override
        protected List<String> packages() {
            return List.of(R + "ai", R + "chat", R + "search", R + "credits");
        }
    }
}
