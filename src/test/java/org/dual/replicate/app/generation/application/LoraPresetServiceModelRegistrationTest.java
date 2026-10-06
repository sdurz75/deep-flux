package org.dual.replicate.app.generation.application;

import java.util.Optional;

import org.dual.replicate.app.generation.domain.LoraPreset;
import org.dual.replicate.app.generation.domain.ReplicateException;
import org.dual.replicate.app.generation.port.in.IModelCatalog;
import org.dual.replicate.app.generation.port.out.ILoraPresetStore;
import org.dual.replicate.core.events.port.in.ISystemEvents;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Un LoRA anagrafato con sorgente {@code owner/nome} e' implicitamente un modello Replicate: diventa anche un modello del catalogo. */
class LoraPresetServiceModelRegistrationTest {

    private final ILoraPresetStore store = mock(ILoraPresetStore.class);
    private final Messages messages = mock(Messages.class);
    private final IModelCatalog catalog = mock(IModelCatalog.class);
    private final ISystemEvents events = mock(ISystemEvents.class);
    private final LoraPresetService service = new LoraPresetService(store, messages, catalog, events,
            mock(org.dual.replicate.core.tokens.port.in.IApiTokens.class), java.time.Clock.systemUTC());

    LoraPresetServiceModelRegistrationTest() {
        when(store.save(any(LoraPreset.class))).thenAnswer(i -> i.getArgument(0));
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void anOwnerNameSourceIsRegisteredAsAModelWithThePresetNameOnCreate() {
        service.create("Stile acquerello", "  owner/acquerello ", null, null, null);

        verify(catalog).registerLoraFinetune("owner/acquerello", "Stile acquerello");
    }

    @Test
    void updateRegistersTheNewSource() {
        LoraPreset existing = new LoraPreset("Vecchio", "a/b", 1.0, null, null, java.time.Instant.now());
        when(store.findById(7L)).thenReturn(Optional.of(existing));

        service.update(7L, "Nuovo", "owner/nuovo", 1.0, null, null);

        verify(catalog).registerLoraFinetune("owner/nuovo", "Nuovo");
    }

    @Test
    void urlsAndSafetensorsFilesAreNotReplicateModels() {
        service.create("hf", "https://huggingface.co/owner/model", null, null, null);
        service.create("civit", "https://civitai.com/api/download/models/123", null, null, null);
        service.create("file", "owner/file.safetensors", null, null, null);

        verifyNoInteractions(catalog);
    }

    /** Il preset e' gia' salvato: un modello inesistente o non LoRA e' un rifiuto atteso, senza evento ne' errore all'utente. */
    @Test
    void aRejectedRegistrationKeepsThePresetAndStaysSilent() {
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenThrow(new ReplicateException("non e' un LoRA"));

        var created = service.create("Stile", "owner/stile", null, null, null);

        assertThat(created.source()).isEqualTo("owner/stile");
        verify(store).save(any(LoraPreset.class));
        verify(events, never()).record(anyString(), any(Throwable.class));
    }

    /** Un guasto di Replicate (rete, 5xx) si registra come evento di sistema, ma il preset resta salvato. */
    @Test
    void aServiceFailureIsRecordedAsASystemEventAndThePresetIsKept() {
        var failure = new ReplicateException("giu'", null, Kind.TRANSIENT);
        when(catalog.registerLoraFinetune(anyString(), anyString())).thenThrow(failure);

        var created = service.create("Stile", "owner/stile", null, null, null);

        assertThat(created.name()).isEqualTo("Stile");
        verify(events).record("registerLoraModel", failure);
    }
}
