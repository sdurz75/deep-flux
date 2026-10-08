package org.dual.hexa.oauth2.login.adapter.in.web;

import java.util.ArrayList;
import java.util.List;
import org.dual.hexa.core.lock.port.in.ILockExemptPaths;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * La catena dei filtri di Spring Security, scritta per essere INVISIBILE a cancello spento: la decisione di autorizzazione legge {@code IOAuthAccess#isEnabled}
 * a ogni richiesta (la catena e' statica, l'interruttore e' un dato), CSRF e intestazioni di default sono spenti (a cancello acceso il CSRF lo fa
 * {@code SameOriginFilter}), nessuna password generata. A cancello acceso passa chi e' autenticato e i path esenti: quelli delle librerie
 * ({@code ILockExemptPaths}: manifest, service worker, pagina offline, icone, che il browser chiede senza cookie), gli asset e la pagina di accesso. Viene
 * PRIMA del blocco con PIN (il PIN, se attivo, si chiede dopo l'accesso).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
class OAuthSecurityConfig {

    private static final List<String> ALWAYS_OPEN = List.of("/js/**", "/css/**", "/error", "/favicon.ico", OAuthRequests.LOGIN_PATH, "/oauth2/start/*",
            "/oauth2/authorization/*", "/login/oauth2/code/*");

    @Bean
    SecurityFilterChain hexaOauth2FilterChain(HttpSecurity http, IOAuthAccess access, ClientRegistrationRepository registrations,
                                              ObjectProvider<ILockExemptPaths> libraryPaths, AllowlistOidcUserService users, OAuthEntryPoint entryPoint,
                                              OAuthFailureHandler failureHandler) throws Exception {
        List<String> open = new ArrayList<>(ALWAYS_OPEN);
        libraryPaths.orderedStream().flatMap(library -> library.paths().stream())
                .forEach(path -> open.add(path.endsWith("/") ? path + "**" : path));

        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        // PKCE anche per i client confidenziali (Spring lo attiva da solo solo per quelli pubblici).
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        // Dopo l'accesso si torna a una PAGINA che l'utente aveva chiesto, mai a un fetch, a un fragment htmx o a un flusso SSE finiti nella cache.
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> "GET".equals(request.getMethod()) && OAuthRequests.isNavigation(request));

        http
                .headers(headers -> headers.disable())
                .csrf(csrf -> csrf.disable())
                .anonymous(Customizer.withDefaults())
                .requestCache(cache -> cache.requestCache(requestCache))
                .addFilterAfter(new SameOriginFilter(access), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(open.toArray(String[]::new)).permitAll()
                        .anyRequest().access((authentication, context) -> new AuthorizationDecision(!access.isEnabled() || authenticated(authentication.get()))))
                .oauth2Login(login -> login
                        .loginPage(OAuthRequests.LOGIN_PATH)
                        .clientRegistrationRepository(registrations)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver((OAuth2AuthorizationRequestResolver) resolver))
                        .userInfoEndpoint(info -> info.oidcUserService(users))
                        .failureHandler(failureHandler))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/oauth2/logout"))
                        .logoutSuccessUrl(OAuthRequests.LOGIN_PATH));
        return http.build();
    }

    private static boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
