package ${package}.example.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ${package}.example.domain.ExampleEventSource;
import ${package}.example.domain.ExampleItem;
import ${package}.example.port.out.IExampleStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.hexa.core.storage.domain.StorageException;
import org.dual.hexa.core.storage.domain.UploadedFile;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.Test;

/** Test senza contesto Spring: lo store e' una porta (qui un finto in memoria), storage ed eventi di sistema sono mock delle porte {@code in} di hexa-core. */
class ExampleServiceTest {

    private final List<ExampleItem> saved = new ArrayList<>();
    private final IExampleStore store = new IExampleStore() {
        @Override
        public List<ExampleItem> findNewestFirst() {
            return saved.reversed();
        }

        @Override
        public Optional<ExampleItem> findById(Long id) {
            return saved.stream().filter(item -> id.equals(item.getId())).findFirst();
        }

        @Override
        public ExampleItem save(ExampleItem item) {
            saved.add(item);
            return item;
        }

        @Override
        public void delete(ExampleItem item) {
            saved.remove(item);
        }
    };
    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final ISystemEvents systemEvents = mock(ISystemEvents.class);
    private final ExampleService service = new ExampleService(store, storage, systemEvents);

    private static final UploadedFile PNG = UploadedFile.of("a.png", new byte[] {1, 2, 3});

    @Test
    void addTrimsTheTitleAndListIsNewestFirst() {
        service.add("  primo ", null);
        service.add("secondo", null);

        assertThat(service.list()).extracting(ExampleItem::getTitle).containsExactly("secondo", "primo");
    }

    @Test
    void aBlankTitleIsRejectedAndNothingIsSavedOrStored() {
        assertThatThrownBy(() -> service.add("   ", PNG)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(null, null)).isInstanceOf(IllegalArgumentException.class);

        assertThat(saved).isEmpty();
        verify(storage, never()).storeUpload(any());
    }

    @Test
    void theAttachmentGoesThroughTheStoragePortAndItsOpaqueNameIsKept() {
        when(storage.storeUpload(PNG)).thenReturn("ab12.png");

        ExampleItem item = service.add("con allegato", PNG);

        assertThat(item.getAttachmentFilename()).isEqualTo("ab12.png");
    }

    @Test
    void anEmptyUploadIsNoAttachment() {
        ExampleItem item = service.add("vuoto", new UploadedFile("", 0, () -> new java.io.ByteArrayInputStream(new byte[0])));

        assertThat(item.getAttachmentFilename()).isNull();
        verify(storage, never()).storeUpload(any());
    }

    @Test
    void aRejectedUploadLeavesNoRow() {
        when(storage.storeUpload(PNG)).thenThrow(new StorageException("tipo non valido", null, Kind.REJECTED));

        assertThatThrownBy(() -> service.add("x", PNG)).isInstanceOf(StorageException.class);

        assertThat(saved).isEmpty();
    }

    private static ExampleItem item(long id, String attachment) {
        return new ExampleItem("x", attachment) {
            @Override
            public Long getId() {
                return id;
            }
        };
    }

    @Test
    void deletingRemovesTheRowAndTheFileAndIsIdempotent() {
        saved.add(item(7, "ab12.png"));

        service.delete(7L);
        service.delete(7L);

        assertThat(saved).isEmpty();
        verify(storage).delete("ab12.png");
    }

    @Test
    void aFileThatCannotBeDeletedIsRecordedNotSwallowedNorFatal() {
        saved.add(item(9, "ab12.png"));
        StorageException failure = new StorageException("disco", null, Kind.PERMANENT);
        doThrow(failure).when(storage).delete("ab12.png");

        service.delete(9L);

        assertThat(saved).isEmpty();
        verify(systemEvents).record(eq(ExampleEventSource.EXAMPLE), eq("deleteAttachment"), eq(failure), eq("example:9"));
    }
}
