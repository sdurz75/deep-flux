package org.dual.replicate.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Blob cifrato su un file locale (la cache, o un temporaneo appena prodotto). */
final class FileBlobSource implements EncryptedBlobSource {

    private final Path file;

    FileBlobSource(Path file) {
        this.file = file;
    }

    @Override
    public long length() throws IOException {
        return Files.size(file);
    }

    @Override
    public InputStream read(long offset, long len) throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            in.skipNBytes(offset);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
        return Streams.limit(in, len);
    }
}
