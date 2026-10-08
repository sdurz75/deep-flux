package org.dual.hexa.core.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.dual.hexa.core.config.domain.ConfigChange;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.ModuleValues;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.out.IModuleConfigStore;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.secrets.port.in.ISecrets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

/** Precedenza dei valori (DB, property, default), validazione tutto-o-niente e registro dei moduli, senza Spring. */
class ModuleConfigServiceTest {

    private final IModuleConfigStore store = mock(IModuleConfigStore.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final Messages messages = mock(Messages.class);
    private final Map<String, String> stored = new HashMap<>();
    private final ISecrets secrets = mock(ISecrets.class);
    private ModuleConfigService service;

    @BeforeEach
    void setUp() {
        when(messages.get(any(String.class), any(Object[].class))).thenReturn("rifiutato");
        when(store.load("demo")).thenAnswer(call -> new HashMap<>(stored));
        service = new ModuleConfigService(providerOf(new DemoModule("demo")), store, environment, messages, mock(ApplicationEventPublisher.class), secrets);
    }

    @Test
    void aDatabaseOverrideBeatsAPropertyWhichBeatsTheDefault() {
        assertThat(service.values("demo").getInt("limit")).isEqualTo(5);

        environment.setProperty("app.demo.limit", "7");
        assertThat(service.values("demo").getInt("limit")).isEqualTo(7);

        stored.put("limit", "9");
        service = new ModuleConfigService(providerOf(new DemoModule("demo")), store, environment, messages, mock(ApplicationEventPublisher.class), secrets);
        assertThat(service.values("demo").getInt("limit")).isEqualTo(9);
    }

    @Test
    void anUnreadableIntegerFallsBackToTheDefault() {
        environment.setProperty("app.demo.limit", "abc");

        assertThat(service.values("demo").getInt("limit")).isEqualTo(5);
    }

    @Test
    void anInvalidValueSavesNothingAtAll() {
        assertThatThrownBy(() -> service.save("demo", Map.of("limit", "3", "color", "rosso"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> service.save("demo", Map.of("limit", "99"))).isInstanceOf(ConfigException.class);

        verify(store, never()).saveAll(any(), any());
    }

    @Test
    void anAbsentBooleanMeansOffWhileOtherAbsentFieldsAreLeftAlone() {
        service.save("demo", Map.of("limit", "3"));

        verify(store).saveAll("demo", Map.of("limit", "3", "flag", "false"));
    }

    @Test
    void twoModulesWithTheSameIdFailAtStartup() {
        assertThatThrownBy(() -> new ModuleConfigService(providerOf(new DemoModule("demo"), new DemoModule("demo")), store, environment, messages,
                mock(ApplicationEventPublisher.class), secrets)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownModuleIsRejected() {
        assertThat(service.module("nope")).isEmpty();
        assertThatThrownBy(() -> service.values("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- SECRET, LIST, COLLECTION e hook ---------------------------------------------------------------------------------

    private ModuleConfigService richService(RichModule module) {
        when(store.load("rich")).thenAnswer(call -> new HashMap<>(stored));
        when(secrets.isConfigured()).thenReturn(true);
        return new ModuleConfigService(providerOf(module), store, environment, messages, mock(ApplicationEventPublisher.class), secrets);
    }

    @Test
    void aSecretIsStoredAsASecretOfItsTypeAndNeverInModuleConfig() {
        service = richService(new RichModule());

        service.save("rich", Map.of("token", "  s3cret-value "));

        verify(secrets).store("MODULE_TYPE", "rich/token", "s3cret-value");
        verify(store).saveAll(org.mockito.ArgumentMatchers.eq("rich"), org.mockito.ArgumentMatchers.argThat(m -> !m.containsKey("token") && !m.toString().contains("s3cret")));
        when(secrets.resolveByName("MODULE_TYPE", "rich/token")).thenReturn(Optional.of("s3cret-value"));
        assertThat(service.values("rich").getSecret("token")).contains("s3cret-value");
        assertThat(service.values("rich").toString()).doesNotContain("s3cret");
    }

    @Test
    void anEmptySecretLeavesItAndWithoutAKeyNothingIsWritten() {
        service = richService(new RichModule());
        service.save("rich", Map.of("token", ""));
        verify(secrets, never()).store(any(), any(), any());

        when(secrets.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.save("rich", Map.of("token", "x"))).isInstanceOf(ConfigException.class);
        verify(store, never()).saveAll(org.mockito.ArgumentMatchers.eq("rich"), org.mockito.ArgumentMatchers.argThat(m -> m.containsKey("emails")));
    }

    @Test
    void anAdditiveListSumsDatabasePropertyAndEnvironmentWhileAPlainOneIsReplaced() {
        service = richService(new RichModule());
        stored.put("emails", "uno@x.it\nDue@x.it");
        environment.setProperty("app.rich.emails", "tre@x.it, uno@x.it");
        environment.setProperty("RICH_EMAILS", "quattro@x.it");
        environment.setProperty("RICH_TAGS", "env-tag");
        environment.setProperty("app.rich.tags", "prop-tag");

        assertThat(service.values("rich").getList("emails")).containsExactly("uno@x.it", "due@x.it", "tre@x.it", "quattro@x.it");
        assertThat(service.values("rich").getList("tags")).containsExactly("prop-tag"); // property batte ambiente
        stored.put("tags", "salvato");
        service = richService(new RichModule()); // la cache degli override e' per istanza
        assertThat(service.values("rich").getList("tags")).containsExactly("salvato"); // DB batte tutto

        var state = service.formState("rich");
        assertThat(state.values().get("emails")).isEqualTo("uno@x.it\ndue@x.it"); // solo le salvate
        assertThat(state.environment().get("emails")).containsExactly("tre@x.it", "quattro@x.it");
    }

    @Test
    void listEntriesAreNormalisedAndValidatedWithAllOrNothing() {
        service = richService(new RichModule());

        service.save("rich", Map.of("emails", " A@X.it \n\n a@x.it\nb@x.it"));
        verify(store).saveAll("rich", Map.of("emails", "a@x.it\nb@x.it"));

        assertThatThrownBy(() -> service.save("rich", Map.of("emails", "ok@x.it\nnon-una-mail"))).isInstanceOf(ConfigException.class);
    }

    @Test
    void aCollectionKeepsRowsWithoutSecretsAndSecretsPerRowAreNamedByRowAndColumn() {
        service = richService(new RichModule());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("items.__new.slug", "google");
        form.put("items.__new.title", "Google");
        form.put("items.__new.kind", "a");
        form.put("items.__new.secret", "client-secret-1");

        service.save("rich", form);

        verify(secrets).store("MODULE_TYPE", "rich/items/google/secret", "client-secret-1");
        verify(store).saveAll(org.mockito.ArgumentMatchers.eq("rich"), org.mockito.ArgumentMatchers.argThat(m ->
                m.get("items").contains("\"slug\":\"google\"") && m.get("items").contains("\"title\":\"Google\"") && !m.get("items").contains("client-secret")));
    }

    @Test
    void anExistingRowIsUpdatedKeepingItsSecretAndARemovedRowDeletesItsSecrets() {
        service = richService(new RichModule());
        stored.put("items", "[{\"slug\":\"google\",\"title\":\"Google\",\"kind\":\"a\"},{\"slug\":\"altro\",\"title\":\"Altro\",\"kind\":\"b\"}]");
        assertThat(service.values("rich").getRows("items")).extracting(ModuleValues.Row::id).containsExactly("google", "altro");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("items.google.title", "Google 2");
        form.put("items.google.secret", "");
        form.put("items.altro.__remove", "true");
        service.save("rich", form);

        verify(secrets, never()).store(any(), any(), any());
        verify(secrets).deleteByNamePrefix("MODULE_TYPE", "rich/items/altro/");
        verify(store).saveAll(org.mockito.ArgumentMatchers.eq("rich"), org.mockito.ArgumentMatchers.argThat(m ->
                m.get("items").contains("Google 2") && !m.get("items").contains("altro")));
    }

    @Test
    void collectionRulesAreEnforcedForIdsDuplicatesRequiredColumnsAndTheRowLimit() {
        service = richService(new RichModule());
        stored.put("items", "[{\"slug\":\"a\",\"title\":\"A\",\"kind\":\"a\"},{\"slug\":\"b\",\"title\":\"B\",\"kind\":\"a\"}]");

        assertThatThrownBy(() -> service.save("rich", Map.of("items.__new.slug", "Maiuscola", "items.__new.title", "t", "items.__new.secret", "s", "items.__new.kind", "a")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> service.save("rich", Map.of("items.__new.slug", "a", "items.__new.title", "t", "items.__new.secret", "s", "items.__new.kind", "a")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> service.save("rich", Map.of("items.__new.slug", "c", "items.__new.title", "", "items.__new.secret", "s", "items.__new.kind", "a")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> service.save("rich", Map.of("items.__new.slug", "c", "items.__new.title", "t", "items.__new.secret", "s", "items.__new.kind", "a")))
                .isInstanceOf(ConfigException.class); // max 2 righe
        verify(secrets, never()).store(any(), any(), any());
    }

    @Test
    void theValidationHookSeesCurrentAndProposedValuesAndCanVetoEverything() {
        RichModule module = new RichModule();
        service = richService(module);
        stored.put("tags", "vecchio");

        service.save("rich", Map.of("tags", "nuovo"));
        assertThat(module.seenCurrent).containsExactly("vecchio");
        assertThat(module.seenProposed).containsExactly("nuovo");

        module.veto = true;
        assertThatThrownBy(() -> service.save("rich", Map.of("tags", "altro"))).isInstanceOf(ConfigException.class).hasMessage("rifiutato");
        verify(store, org.mockito.Mockito.times(1)).saveAll(any(), any());
        verify(secrets, never()).store(any(), any(), any());
    }

    @Test
    void resetDeletesTheModulesSecretsToo() {
        service = richService(new RichModule());

        service.reset("rich");

        verify(secrets).deleteByName("MODULE_TYPE", "rich/token");
        verify(secrets).deleteByNamePrefix("MODULE_TYPE", "rich/items/");
    }

    @Test
    void aSecretNameThatCannotFitIsRefusedAtStartup() {
        IConfigModule tooLong = new IConfigModule() {
            @Override public String id() { return "a-very-long-module-identifier"; }
            @Override public String titleKey() { return "x"; }
            @Override public List<ConfigField> fields() {
                return List.of(ConfigField.collection("a-very-long-collection-key", "l", null,
                        List.of(ConfigField.Column.text("slug", "l", null, null, true), ConfigField.Column.secret("client-secret-column", "l")), "slug", 3, "T"));
            }
        };

        assertThatThrownBy(() -> new ModuleConfigService(providerOf(tooLong), store, environment, messages, mock(ApplicationEventPublisher.class), secrets))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class RichModule implements IConfigModule {
        List<String> seenCurrent;
        List<String> seenProposed;
        boolean veto;

        @Override public String id() { return "rich"; }
        @Override public String titleKey() { return "rich.title"; }

        @Override
        public List<ConfigField> fields() {
            return List.of(
                    ConfigField.secret("token", "MODULE_TYPE", "rich.token", null),
                    ConfigField.list("emails", "rich.emails", null, "RICH_EMAILS", "[^@\\s]+@[^@\\s]+", null, true, true),
                    ConfigField.list("tags", "rich.tags", null, "RICH_TAGS", null, null, false, false),
                    ConfigField.collection("items", "rich.items", null, List.of(
                            ConfigField.Column.text("slug", "rich.slug", null, null, true),
                            ConfigField.Column.text("title", "rich.title2", null, null, true),
                            ConfigField.Column.select("kind", "rich.kind", List.of("a", "b")),
                            ConfigField.Column.secret("secret", "rich.secret")), "slug", 2, "MODULE_TYPE"));
        }

        @Override
        public void validate(ConfigChange change) {
            seenCurrent = change.current().getList("tags");
            seenProposed = change.proposed().getList("tags");
            if (veto) {
                throw new ConfigException("rifiutato");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<IConfigModule> providerOf(IConfigModule... modules) {
        ObjectProvider<IConfigModule> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> Stream.of(modules));
        return provider;
    }

    private record DemoModule(String id) implements IConfigModule {

        @Override
        public String titleKey() {
            return "demo.title";
        }

        @Override
        public List<ConfigField> fields() {
            return List.of(ConfigField.integer("limit", 5, 1, 10, "demo.limit", null),
                    ConfigField.color("color", "#ffffff", "demo.color", null),
                    ConfigField.bool("flag", true, "demo.flag", null));
        }
    }
}
