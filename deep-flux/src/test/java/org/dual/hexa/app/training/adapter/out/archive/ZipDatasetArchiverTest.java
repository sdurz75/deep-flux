package org.dual.hexa.app.training.adapter.out.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.dual.hexa.app.training.domain.ArchiveItem;
import org.dual.hexa.app.training.domain.DatasetArchive;
import org.dual.hexa.app.training.domain.TrainingException;
import org.dual.hexa.core.kernel.i18n.Messages;
import org.dual.hexa.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.hexa.core.storage.domain.SourceImage;
import org.dual.hexa.core.storage.domain.StorageException;
import org.dual.hexa.core.storage.port.in.IImageStorageService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lo zip vero (java.util.zip su un file temporaneo), con lo storage mockato: nessun file del progetto, nessuna rete. */
class ZipDatasetArchiverTest {

    private final IImageStorageService storage = mock(IImageStorageService.class);
    private final Messages messages = mock(Messages.class);
    private final ZipDatasetArchiver archiver = new ZipDatasetArchiver(storage, messages);

    ZipDatasetArchiverTest() {
        when(messages.get(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));
        when(messages.get(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void theZipHasEachImageAndItsCaptionFileWithTheSameBaseName() throws IOException {
        when(storage.read("a.jpg")).thenReturn(new SourceImage(new byte[] {1, 2, 3}, "image/jpeg"));
        when(storage.read("b.png")).thenReturn(new SourceImage(new byte[] {4, 5}, "image/png"));

        try (DatasetArchive zip = archiver.build(List.of(
                new ArchiveItem("img_001.jpg", "a.jpg", "img_001.txt", "TOK, un gatto sul divano"),
                new ArchiveItem("img_002.png", "b.png", "img_002.txt", "TOK, àèìòù ✓")))) {
            Map<String, byte[]> entries = unzip(zip);

            assertThat(entries).containsOnlyKeys("img_001.jpg", "img_001.txt", "img_002.png", "img_002.txt");
            assertThat(entries.get("img_001.jpg")).containsExactly(1, 2, 3);
            assertThat(entries.get("img_002.png")).containsExactly(4, 5);
            assertThat(new String(entries.get("img_001.txt"), StandardCharsets.UTF_8)).isEqualTo("TOK, un gatto sul divano");
            assertThat(new String(entries.get("img_002.txt"), StandardCharsets.UTF_8)).as("UTF-8").isEqualTo("TOK, àèìòù ✓");
        }
    }

    @Test
    void sizeIsTheRealSizeOfTheFileAndOpenCanBeCalledManyTimes() throws IOException {
        when(storage.read(anyString())).thenReturn(new SourceImage(new byte[100], "image/jpeg"));

        try (DatasetArchive zip = archiver.build(List.of(new ArchiveItem("img_001.jpg", "a.jpg", "img_001.txt", "x")))) {
            byte[] first = zip.open().readAllBytes();
            byte[] second = zip.open().readAllBytes();

            assertThat(first).hasSize((int) zip.size()).isEqualTo(second);
        }
    }

    @Test
    void closingTheArchiveDeletesTheTemporaryFile() throws IOException {
        when(storage.read(anyString())).thenReturn(new SourceImage(new byte[] {1}, "image/jpeg"));
        long before = temporaryZips();

        DatasetArchive zip = archiver.build(List.of(new ArchiveItem("img_001.jpg", "a.jpg", "img_001.txt", "x")));
        assertThat(temporaryZips()).isEqualTo(before + 1);
        zip.close();

        assertThat(temporaryZips()).isEqualTo(before);
        zip.close(); // chiuderlo due volte non e' un errore
    }

    @Test
    void anUnreadableImageFailsTheWholeArchiveAndLeavesNoTemporaryFile() throws IOException {
        when(storage.read("a.jpg")).thenReturn(new SourceImage(new byte[] {1}, "image/jpeg"));
        when(storage.read("sparita.jpg")).thenThrow(new StorageException("non c'e'", null, Kind.REJECTED));
        long before = temporaryZips();

        assertThatThrownBy(() -> archiver.build(List.of(new ArchiveItem("img_001.jpg", "a.jpg", "img_001.txt", "x"),
                new ArchiveItem("img_002.jpg", "sparita.jpg", "img_002.txt", "y"))))
                .isInstanceOfSatisfying(TrainingException.class, e -> assertThat(e.getMessage()).isEqualTo("training.error.archiveFileUnreadable"));

        assertThat(temporaryZips()).as("un dataset a meta' non si manda a un servizio a pagamento, e non lascia file").isEqualTo(before);
    }

    @Test
    void aBackendFailureIsReportableButAMissingFileIsAnExpectedRefusal() {
        when(storage.read("rotto.jpg")).thenThrow(new StorageException("webdav giu'", null, Kind.TRANSIENT));
        when(storage.read("sparita.jpg")).thenThrow(new StorageException("non c'e'", null, Kind.REJECTED));

        assertThatThrownBy(() -> archiver.build(List.of(new ArchiveItem("img_001.jpg", "rotto.jpg", "img_001.txt", "x"))))
                .isInstanceOfSatisfying(TrainingException.class, e -> assertThat(e.isReportable()).isTrue());
        assertThatThrownBy(() -> archiver.build(List.of(new ArchiveItem("img_001.jpg", "sparita.jpg", "img_001.txt", "x"))))
                .isInstanceOfSatisfying(TrainingException.class, e -> assertThat(e.isReportable()).isFalse());
    }

    private static Map<String, byte[]> unzip(DatasetArchive zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = zip.open(); ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(in.readAllBytes()))) {
            for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    /** Quanti zip di addestramento stanno nella cartella temporanea (per provare che si ripuliscono). */
    private static long temporaryZips() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var files = Files.list(tmp)) {
            List<Path> found = new ArrayList<>(files.filter(p -> p.getFileName().toString().startsWith("training-dataset-")).toList());
            return found.size();
        }
    }
}
