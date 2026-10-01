package org.dual.replicate.core.storage.adapter.out.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.OptionalLong;

import org.dual.replicate.core.storage.port.out.IBlobBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static org.dual.replicate.core.storage.domain.StorageNames.checkFilename;
import static org.dual.replicate.core.storage.domain.StorageNames.shardPath;

/**
 * Backend su filesystem locale: i file vivono sotto storage.images-dir, fuori da static/ perche' sono stato
 * applicativo prodotto a runtime, non asset del progetto (li serve ImageController via /images/**). Default
 * ({@code storage.type=local}).
 */
@Component
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFsBlobBackend implements IBlobBackend {

    private final Path imagesDir;

    public LocalFsBlobBackend(@Value("${storage.images-dir}") String imagesDir) {
        this.imagesDir = Path.of(imagesDir);
    }

    /**
     * In streaming su un file TEMPORANEO poi spostato sul nome definitivo in modo atomico: un download interrotto o
     * troncato non lascia mai un file parziale col nome "buono" (che la galleria servirebbe come immagine rotta), e a
     * fallimento il temporaneo viene rimosso.
     */
    @Override
    public void write(String filename, InputStream in) throws IOException {
        checkFilename(filename);
        Path target = resolve(filename);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(filename + ".part");
        try {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    @Override
    public void remove(String filename) throws IOException {
        Files.deleteIfExists(resolve(filename));
    }

    @Override
    public OptionalLong size(String filename) {
        Path file = resolve(filename);
        try {
            return Files.isRegularFile(file) ? OptionalLong.of(Files.size(file)) : OptionalLong.empty();
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public InputStream openRange(String filename, long offset, long length) throws IOException {
        Path file = resolve(filename);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(filename);
        }
        return new FileBlobSource(file).read(offset, length);
    }

    /** Confinato sotto storage.images-dir. */
    private Path resolve(String filename) {
        checkFilename(filename);
        Path file = imagesDir.resolve(shardPath(filename)).normalize();
        if (!file.startsWith(imagesDir.normalize())) {
            throw new IllegalArgumentException(filename);
        }
        return file;
    }
}
