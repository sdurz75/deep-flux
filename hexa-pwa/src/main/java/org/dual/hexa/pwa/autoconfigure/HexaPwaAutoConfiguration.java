package org.dual.hexa.pwa.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.FilterType;

/**
 * Registra hexa-pwa (manifest, service worker, pagina offline) in un'applicazione Spring Boot qualunque sia il suo package radice. Nessuna entity,
 * nessuna migrazione, nessun file di configurazione da importare: basta la dipendenza. {@code app.pwa.enabled=false} o {@code HX_PWA_ENABLED=false} spegne tutto (default acceso, la property prevale). Il bundle
 * {@code messages-pwa} lo aggiunge {@code PwaMessagesConfig}.
 */
@AutoConfiguration(afterName = "org.dual.hexa.core.autoconfigure.HexaCoreAutoConfiguration")
@Conditional(PwaEnabledCondition.class)
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)},
        basePackages = HexaPwaAutoConfiguration.R + "shell")
public class HexaPwaAutoConfiguration {

    /** Radice del sottosistema, spezzata di proposito: i test di architettura leggono i sorgenti e vedrebbero una dipendenza. */
    static final String R = "org.dual.hexa" + ".pwa.";
}
