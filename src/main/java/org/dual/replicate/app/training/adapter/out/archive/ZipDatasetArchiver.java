package org.dual.replicate.app.training.adapter.out.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.dual.replicate.app.training.domain.ArchiveItem;
import org.dual.replicate.app.training.domain.DatasetArchive;
import org.dual.replicate.app.training.domain.TrainingException;
import org.dual.replicate.app.training.port.out.IDatasetArchiver;
import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.springframework.stereotype.Component;

/**
 * {@link IDatasetArchiver} con {@code java.util.zip}: un file temporaneo con le immagini e, accanto a ciascuna, il {@code .txt} con la didascalia (UTF-8, stesso
 * nome). Si scrive su disco e non in memoria: 25 immagini da 10 MB sono 250 MB. Le immagini si leggono dallo storage una alla volta ({@link IImageStorageService}),
 * quindi lo zip e' indipendente da dove stanno i file (locale o WebDAV).
 */
@Component
class ZipDatasetArchiver implements IDatasetArchiver {

    private final IImageStorageService storage;
    private final Messages messages;

    ZipDatasetArchiver(IImageStorageService storage, Messages messages) {
        this.storage = storage;
        this.messages = messages;
    }

    @Override
    public DatasetArchive build(List<ArchiveItem> items) {
        Path file = null;
        try {
            file = Files.createTempFile("training-dataset-", ".zip");
            try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
                for (ArchiveItem item : items) {
                    SourceImage image = read(item);
                    zip.putNextEntry(new ZipEntry(item.imageEntry()));
                    zip.write(image.bytes());
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry(item.captionEntry()));
                    zip.write(item.caption().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return new TempFileArchive(file);
        } catch (IOException e) {
            deleteQuietly(file);
            throw new TrainingException(messages.get("training.error.archiveFailed", e.getMessage()), e, Kind.PERMANENT);
        } catch (RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
    }

    private SourceImage read(ArchiveItem item) {
        try {
            return storage.read(item.filename());
        } catch (StorageException e) {
            // Un file dello snapshot che non c'e' piu' o non si legge: i dati sono incompleti, non si manda un dataset a meta' a un servizio a pagamento.
            throw new TrainingException(messages.get("training.error.archiveFileUnreadable", item.imageEntry(), e.getMessage()), e,
                    e.isReportable() ? Kind.PERMANENT : Kind.REJECTED);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // un file temporaneo che resta lo ripulisce il sistema
            }
        }
    }

    /** Lo zip in un file temporaneo: {@code open()} lo rilegge dall'inizio, {@code close()} lo elimina. */
    private static final class TempFileArchive implements DatasetArchive {

        private final Path file;
        private final long size;

        TempFileArchive(Path file) throws IOException {
            this.file = file;
            this.size = Files.size(file);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public InputStream open() {
            try {
                return Files.newInputStream(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            deleteQuietly(file);
        }
    }
}
