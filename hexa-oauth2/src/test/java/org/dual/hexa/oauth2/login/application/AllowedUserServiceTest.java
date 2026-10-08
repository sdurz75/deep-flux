package org.dual.hexa.oauth2.login.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedUser;
import org.dual.hexa.oauth2.login.domain.OAuthException;
import org.dual.hexa.oauth2.login.port.in.IOAuthAccess;
import org.dual.hexa.oauth2.login.port.out.IAllowedUserStore;
import org.dual.hexa.oauth2.login.port.out.IOAuthEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

class AllowedUserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    private final IAllowedUserStore store = mock(IAllowedUserStore.class);
    private final IOAuthEnvironment env = mock(IOAuthEnvironment.class);
    private final IOAuthAccess access = mock(IOAuthAccess.class);
    private AllowedUserService service;

    @BeforeEach
    void setUp() {
        StaticMessageSource source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        when(env.allowedEmails()).thenReturn(List.of());
        when(env.allowedDomains()).thenReturn(List.of());
        when(store.findByKindAndValue(any(), any())).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new AllowedUserService(store, env, access, new Messages(source), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anEmailIsNormalizedBeforeItIsSaved() {
        var entry = service.add(AllowKind.EMAIL, "  Me@Example.COM ");

        assertThat(entry.value()).isEqualTo("me@example.com");
        assertThat(entry.fromEnv()).isFalse();
    }

    @Test
    void aDomainLosesTheAtSignAndMalformedValuesAreRejected() {
        assertThat(service.add(AllowKind.DOMAIN, "@Example.com").value()).isEqualTo("example.com");

        assertThatThrownBy(() -> service.add(AllowKind.EMAIL, "not-an-email")).isInstanceOf(OAuthException.class).hasMessage("oauth2.error.emailFormat");
        assertThatThrownBy(() -> service.add(AllowKind.DOMAIN, "me@example.com")).hasMessage("oauth2.error.domainFormat");
        assertThatThrownBy(() -> service.add(null, "x")).hasMessage("oauth2.error.kindRequired");
    }

    @Test
    void aDuplicateOfASavedOrEnvironmentEntryIsRefused() {
        when(env.allowedEmails()).thenReturn(List.of("env@example.com"));
        when(store.findByKindAndValue(AllowKind.EMAIL, "saved@example.com")).thenReturn(Optional.of(new AllowedUser(AllowKind.EMAIL, "saved@example.com", NOW)));

        assertThatThrownBy(() -> service.add(AllowKind.EMAIL, "ENV@example.com")).hasMessage("oauth2.error.entryDuplicate");
        assertThatThrownBy(() -> service.add(AllowKind.EMAIL, "saved@example.com")).hasMessage("oauth2.error.entryDuplicate");
    }

    @Test
    void withTheGateOnTheLastUsefulEntryCannotBeRemoved() {
        AllowedUser only = new AllowedUser(AllowKind.EMAIL, "me@example.com", NOW);
        when(store.findById(1L)).thenReturn(Optional.of(only));
        when(store.count()).thenReturn(1L);
        when(access.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.remove(1L)).hasMessage("oauth2.error.lastAllowed");
        verify(store, never()).delete(any());
    }

    @Test
    void anEnvironmentEntryCountsAsAnotherEntryAndTheGateOffAllowsAnyRemoval() {
        AllowedUser only = new AllowedUser(AllowKind.EMAIL, "me@example.com", NOW);
        when(store.findById(1L)).thenReturn(Optional.of(only));
        when(store.count()).thenReturn(1L);
        when(access.isEnabled()).thenReturn(true);
        when(env.allowedDomains()).thenReturn(List.of("corp.example"));

        service.remove(1L);
        verify(store).delete(only);

        when(env.allowedDomains()).thenReturn(List.of());
        when(access.isEnabled()).thenReturn(false);
        service.remove(1L);
    }

    @Test
    void theListShowsEnvironmentEntriesFirstAsReadOnly() {
        when(env.allowedEmails()).thenReturn(List.of("env@example.com"));
        when(store.findAll()).thenReturn(List.of(new AllowedUser(AllowKind.DOMAIN, "example.com", NOW)));

        var list = service.list();

        assertThat(list).extracting("value").containsExactly("env@example.com", "example.com");
        assertThat(list.get(0).fromEnv()).isTrue();
        assertThat(list.get(0).id()).isNull();
    }
}
