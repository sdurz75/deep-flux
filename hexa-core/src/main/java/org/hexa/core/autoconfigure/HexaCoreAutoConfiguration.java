package org.hexa.core.autoconfigure;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Registra hexa-core in un'applicazione Spring Boot qualunque sia il suo package radice: i componenti dei sottosistemi
 * ({@code org.hexa.core.*}) e i loro entity/repository JPA (via {@code AutoConfigurationPackages}, che alimenta anche l'entity scan e i
 * repository di Spring Data). Senza, funzionerebbe solo con un host in {@code org.hexa}.
 *
 * <p>I package sono stringhe e non classi: questa classe non deve dipendere dai sottosistemi (rispetterebbe male il grafo di
 * {@code ArchitectureTest}). Resta a carico dell'host, in {@code application.yml}: {@code spring.config.import} di {@code classpath:core.yml},
 * il proprio {@code spring.flyway.locations} se non usa il default ({@code classpath:db/migration}: le sottocartelle {@code core}, {@code ai} e le sue
 * si trovano da sole) e i punti di estensione (nav, status-extras, SPI).
 *
 * <p>Gli stessi filtri di {@code @SpringBootApplication} (esclude le {@code @TestConfiguration} dei test e le autoconfigurazioni). Il package
 * dell'autoconfigurazione NON e' fra quelli scansionati.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@Import(HexaCoreAutoConfiguration.Packages.class)
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)},
        basePackages = {HexaCoreAutoConfiguration.R + "kernel", HexaCoreAutoConfiguration.R + "web", HexaCoreAutoConfiguration.R + "events", HexaCoreAutoConfiguration.R + "push", HexaCoreAutoConfiguration.R + "secrets", HexaCoreAutoConfiguration.R + "tokens", HexaCoreAutoConfiguration.R + "storage", HexaCoreAutoConfiguration.R + "backup", HexaCoreAutoConfiguration.R + "manual"})
public class HexaCoreAutoConfiguration {

    /** Radice dei sottosistemi, spezzata di proposito: i test di architettura leggono i sorgenti e vedrebbero una dipendenza da ognuno. */
    static final String R = "org.hexa" + ".core.";

    /** Package JPA dei sottosistemi (vedi {@code SubsystemPackagesRegistrar}). */
    static class Packages extends SubsystemPackagesRegistrar {
        @Override
        protected List<String> packages() {
            return List.of(R + "kernel", R + "web", R + "events", R + "push", R + "secrets", R + "tokens", R + "storage", R + "backup", R + "manual");
        }
    }
}
