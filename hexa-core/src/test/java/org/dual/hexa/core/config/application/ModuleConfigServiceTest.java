package org.dual.hexa.core.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.dual.hexa.core.config.domain.ConfigException;
import org.dual.hexa.core.config.domain.ConfigField;
import org.dual.hexa.core.config.port.in.IConfigModule;
import org.dual.hexa.core.config.port.out.IModuleConfigStore;
import org.dual.hexa.core.kernel.i18n.Messages;
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
    private ModuleConfigService service;

    @BeforeEach
    void setUp() {
        when(messages.get(any(String.class), any(Object[].class))).thenReturn("rifiutato");
        when(store.load("demo")).thenAnswer(call -> new HashMap<>(stored));
        service = new ModuleConfigService(providerOf(new DemoModule("demo")), store, environment, messages, mock(ApplicationEventPublisher.class));
    }

    @Test
    void aDatabaseOverrideBeatsAPropertyWhichBeatsTheDefault() {
        assertThat(service.values("demo").getInt("limit")).isEqualTo(5);

        environment.setProperty("app.demo.limit", "7");
        assertThat(service.values("demo").getInt("limit")).isEqualTo(7);

        stored.put("limit", "9");
        service = new ModuleConfigService(providerOf(new DemoModule("demo")), store, environment, messages, mock(ApplicationEventPublisher.class));
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
                mock(ApplicationEventPublisher.class))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownModuleIsRejected() {
        assertThat(service.module("nope")).isEmpty();
        assertThatThrownBy(() -> service.values("nope")).isInstanceOf(IllegalArgumentException.class);
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
