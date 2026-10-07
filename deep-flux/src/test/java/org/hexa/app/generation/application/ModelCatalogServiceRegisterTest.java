package org.hexa.app.generation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.hexa.app.generation.domain.GenerationFormType;
import org.hexa.app.generation.domain.ModelVersion;
import org.hexa.app.generation.domain.ReplicateException;
import org.hexa.app.generation.domain.ReplicateModel;
import org.hexa.app.generation.port.out.IModelStore;
import org.hexa.app.generation.port.out.IPredictionGateway;
import org.hexa.core.kernel.i18n.Messages;
import org.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Censimento a runtime di un LoRA addestrato su Replicate: tutto mockato, nessuna chiamata remota. */
class ModelCatalogServiceRegisterTest {

    private final IModelStore store = mock(IModelStore.class);
    private final IPredictionGateway gateway = mock(IPredictionGateway.class);
    private final Messages messages = mock(Messages.class);
    private final Instant now = Instant.parse("2026-10-03T10:00:00Z");
    private final ModelCatalogService catalog = new ModelCatalogService(store, gateway, messages, Clock.fixed(now, ZoneOffset.UTC));

    ModelCatalogServiceRegisterTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(store.nextSortOrder()).thenReturn(8);
        when(store.save(any(ReplicateModel.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void registersTheModelWithItsPinnedLatestVersionAndTheFinetuneFormType() {
        when(gateway.latestVersion("owner/my-lora")).thenReturn(Optional.of(new ModelVersion("hash1", Set.of("prompt", "lora_scale"))));

        Optional<ReplicateModel> registered = catalog.registerLoraFinetune("owner/my-lora", "Il mio stile");

        ArgumentCaptor<ReplicateModel> saved = ArgumentCaptor.forClass(ReplicateModel.class);
        verify(store).save(saved.capture());
        assertThat(registered).containsSame(saved.getValue());
        ReplicateModel m = saved.getValue();
        assertThat(m.getIdentifier()).isEqualTo("owner/my-lora");
        assertThat(m.getVersion()).isEqualTo("hash1");
        assertThat(m.getDescription()).isEqualTo("Il mio stile");
        assertThat(m.getFormType()).isEqualTo(GenerationFormType.FLUX_LORA_FINETUNE);
        assertThat(m.getSortOrder()).isEqualTo(8);
        assertThat(m.isActive()).isTrue();
        assertThat(m.getCreatedAt()).isEqualTo(now);
    }

    /** Idempotente: gia' censito (anche disattivato) = nessuna chiamata remota e nessuna modifica. */
    @Test
    void anAlreadyRegisteredModelIsLeftAlone() {
        when(store.exists("sdurz75", "flux-lora-ff3")).thenReturn(true);

        assertThat(catalog.registerLoraFinetune("sdurz75/flux-lora-ff3", "x")).isEmpty();

        verify(gateway, never()).latestVersion(anyString());
        verify(store, never()).save(any());
    }

    @Test
    void aModelWithoutPublishedVersionsIsRejected() {
        when(gateway.latestVersion("owner/none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.registerLoraFinetune("owner/none", "x"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
        verify(store, never()).save(any());
    }

    /** Uno schema noto senza lora_scale non e' un LoRA di Flux: censirlo darebbe una form sbagliata (e prediction a pagamento che falliscono). */
    @Test
    void aKnownSchemaWithoutLoraScaleIsRejected() {
        when(gateway.latestVersion("owner/sdxl")).thenReturn(Optional.of(new ModelVersion("h", Set.of("prompt"))));

        assertThatThrownBy(() -> catalog.registerLoraFinetune("owner/sdxl", "x"))
                .isInstanceOfSatisfying(ReplicateException.class, e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
        verify(store, never()).save(any());
    }

    @Test
    void anUnknownSchemaIsAccepted() {
        when(gateway.latestVersion("owner/bare")).thenReturn(Optional.of(new ModelVersion("h", Set.of())));

        assertThat(catalog.registerLoraFinetune("owner/bare", "x")).isPresent();
    }
}
