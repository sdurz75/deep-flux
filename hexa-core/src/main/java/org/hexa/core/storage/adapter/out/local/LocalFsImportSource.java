package org.hexa.core.storage.adapter.out.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.hexa.core.storage.domain.ImportableFile;
import org.hexa.core.storage.port.out.IBlobImportSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** I file di {@code storage.images-dir}, anche annidati ({@code ab/cd/<file>}); esiste solo se la migrazione e' abilitata. */
@Component
@ConditionalOnProperty(name = "storage.migration.from-local.enabled", havingValue = "true")
public class LocalFsImportSource implements IBlobImportSource {

    private final Path sourceDir;

    public LocalFsImportSource(@Value("${storage.images-dir}") String imagesDir) {
        this.sourceDir = Path.of(imagesDir);
    }

    @Override
    public List<ImportableFile> list() throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            List<Path> paths = stream.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().endsWith(".part"))
                    .sorted()
                    .toList();
            return paths.stream().map(this::describe).toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void delete(ImportableFile file) throws IOException {
        Files.delete(file.path());
    }

    @Override
    public String toString() {
        return sourceDir.toString();
    }

    private ImportableFile describe(Path path) {
        try {
            return new ImportableFile(path.getFileName().toString(), Files.size(path), path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
