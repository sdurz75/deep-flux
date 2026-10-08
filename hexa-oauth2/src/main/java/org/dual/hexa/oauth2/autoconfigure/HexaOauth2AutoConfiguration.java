package org.dual.hexa.oauth2.autoconfigure;

import java.util.List;
import org.dual.hexa.core.autoconfigure.SubsystemPackagesRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Registra hexa-oauth2 (accesso con OAuth2/OIDC e lista degli utenti ammessi) in un'applicazione Spring Boot qualunque sia il suo package radice:
 * scan dei componenti e package JPA (come {@code HexaAiAutoConfiguration}), package come stringhe spezzate. Nessun file di configurazione da importare:
 * l'essenziale si da' con le variabili {@code HX_OAUTH2_*} (vedi {@code EnvOAuthSettings}). Il bundle {@code messages-oauth2} lo aggiunge
 * {@code Oauth2MessagesConfig}. Va PRIMA delle autoconfigurazioni di Spring Security: la catena dei filtri e il repository dei client sono i suoi, e
 * quelli di default di Spring Boot si ritirano.
 */
@AutoConfiguration(
        afterName = "org.dual.hexa.core.autoconfigure.HexaCoreAutoConfiguration",
        beforeName = {
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
                "org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration",
                "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration"})
@Import(HexaOauth2AutoConfiguration.Packages.class)
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)},
        basePackages = HexaOauth2AutoConfiguration.R + "login")
public class HexaOauth2AutoConfiguration {

    /** Radice del sottosistema, spezzata di proposito: i test di architettura leggono i sorgenti e vedrebbero una dipendenza. */
    static final String R = "org.dual.hexa" + ".oauth2.";

    /** Package JPA del sottosistema (vedi {@code SubsystemPackagesRegistrar}). */
    static class Packages extends SubsystemPackagesRegistrar {
        @Override
        protected List<String> packages() {
            return List.of(R + "login");
        }
    }
}
