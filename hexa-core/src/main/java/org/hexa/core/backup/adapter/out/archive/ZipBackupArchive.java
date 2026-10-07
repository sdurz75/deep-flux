package org.hexa.core.backup.adapter.out.archive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.hexa.core.backup.domain.BackupException;
import org.hexa.core.backup.domain.BackupManifest;
import org.hexa.core.backup.domain.BackupSummary;
import org.hexa.core.backup.port.out.IBackupArchive;
import org.hexa.core.kernel.crypto.ChunkedAesGcmCipher;
import org.hexa.core.kernel.crypto.EncryptedBlobSource;
import org.hexa.core.kernel.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * L'archivio come zip. Voci: {@code manifest.json}, {@code blobs/<file>}, {@code db/<tabella>.copy}, {@code summary.json}, tutte DEFLATED (i binari a
 * livello 0: ZipInputStream non legge le voci STORED senza dimensioni note, e le immagini non si comprimono comunque; Zip64 e' automatico).
 * Cifrato, e' l'INTERO zip a passare dal formato {@code DFX1} di {@link ChunkedAesGcmCipher} (lo stesso dei binari WebDAV e dei token): il
 * contenuto e i nomi delle voci non si vedono, ogni chunk e' autenticato e un file troncato non si autentica. Cifrato o no lo si riconosce dai
 * primi byte ({@code DFX1} o {@code PK}), quindi l'import non ha bisogno di un flag.
 */
@Component
@Profile("backup")
public class ZipBackupArchive implements IBackupArchive {

    private static final Logger log = LoggerFactory.getLogger(ZipBackupArchive.class);
    private static final byte[] ENCRYPTED_MAGIC = {'D', 'F', 'X', '1'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final String MANIFEST = "manifest.json";
    private static final String SUMMARY = "summary.json";
    private static final String BLOBS = "blobs/";
    private static final String TABLES = "db/";
    private static final String TABLE_SUFFIX = ".copy";
    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final JsonMapper json = JsonMapper.builder().build();
    private final Messages messages;

    public ZipBackupArchive(Messages messages) {
        this.messages = messages;
    }

    // --- scrittura ---------------------------------------------------------------------------------------------------------

    @Override
    public Writer create(Path target, byte[] key) {
        Path finalPath = target.toAbsolutePath();
        if (Files.exists(finalPath)) {
            throw new BackupException(messages.get("backup.error.targetExists", finalPath));
        }
        Path part = finalPath.resolveSibling(finalPath.getFileName() + ".part");
        OutputStream file = null;
        try {
            Files.createDirectories(finalPath.getParent());
            file = new BufferedOutputStream(Files.newOutputStream(part));
            OutputStream sink = key == null ? file : new ChunkedAesGcmCipher(key).encryptingStream(file);
            return new ZipWriter(new ZipOutputStream(sink), part, finalPath);
        } catch (IOException e) {
            closeQuietly(file);
            deleteQuietly(part);
            throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
        }
    }

    private final class ZipWriter implements Writer {

        private final ZipOutputStream zip;
        private final Path part;
        private final Path target;
        private boolean finished;

        ZipWriter(ZipOutputStream zip, Path part, Path target) {
            this.zip = zip;
            this.part = part;
            this.target = target;
        }

        @Override
        public void manifest(BackupManifest manifest) {
            putJson(MANIFEST, manifest);
        }

        @Override
        public OutputStream blob(String filename) {
            return entry(BLOBS + filename, Deflater.NO_COMPRESSION);
        }

        @Override
        public OutputStream table(String table) {
            return entry(TABLES + table + TABLE_SUFFIX, Deflater.DEFAULT_COMPRESSION);
        }

        @Override
        public void summary(BackupSummary summary) {
            putJson(SUMMARY, summary);
        }

        @Override
        public void finish() {
            try {
                zip.close();
                Files.move(part, target);
                finished = true;
            } catch (IOException e) {
                throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
            }
        }

        @Override
        public void close() {
            if (!finished) {
                closeQuietly(zip);
                deleteQuietly(part);
            }
        }

        private void putJson(String name, Object value) {
            try (OutputStream out = entry(name, Deflater.DEFAULT_COMPRESSION)) {
                out.write(json.writeValueAsBytes(value));
            } catch (IOException | JacksonException e) {
                throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
            }
        }

        private OutputStream entry(String name, int level) {
            try {
                zip.setLevel(level);
                zip.putNextEntry(new ZipEntry(name));
            } catch (IOException e) {
                throw new BackupException(messages.get("backup.error.io", e.getMessage()), e);
            }
            return new OutputStream() {
                private boolean closed;

                @Override
                public void write(int b) throws IOException {
                    zip.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    zip.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        zip.closeEntry();
                    }
                }
            };
        }
    }

    // --- lettura -----------------------------------------------------------------------------------------------------------

    @Override
    public Reader open(Path source, byte[] key) {
        if (!Files.isRegularFile(source)) {
            throw new BackupException(messages.get("backup.error.notFound", source));
        }
        InputStream plain = null;
        try {
            byte[] magic = new byte[4];
            try (InputStream in = Files.newInputStream(source)) {
                if (in.readNBytes(magic, 0, 4) < 4) {
                    throw new BackupException(messages.get("backup.error.notABackup", source));
                }
            }
            boolean encrypted = Arrays.equals(magic, ENCRYPTED_MAGIC);
            if (encrypted) {
                if (key == null) {
                    throw new BackupException(messages.get("backup.error.archiveNeedsKey"));
                }
                ChunkedAesGcmCipher cipher = new ChunkedAesGcmCipher(key);
                FileSource fileSource = new FileSource(source);
                plain = cipher.decryptRange(fileSource, 0, cipher.plainSize(fileSource));
            } else if (Arrays.equals(magic, ZIP_MAGIC)) {
                plain = Files.newInputStream(source);
            } else {
                throw new BackupException(messages.get("backup.error.notABackup", source));
            }
            ZipReader reader = new ZipReader(new ZipInputStream(new BufferedInputStream(plain, 64 * 1024)), encrypted);
            plain = null; // ora lo chiude il reader
            reader.readManifest();
            return reader;
        } catch (IOException | JacksonException e) {
            closeQuietly(plain);
            throw new BackupException(messages.get("backup.error.cannotOpen", source, String.valueOf(e.getMessage())), e);
        } catch (RuntimeException e) {
            closeQuietly(plain);
            throw e;
        }
    }

    private final class ZipReader implements Reader {

        private final ZipInputStream zip;
        private final boolean encrypted;
        private BackupManifest manifest;

        ZipReader(ZipInputStream zip, boolean encrypted) {
            this.zip = zip;
            this.encrypted = encrypted;
        }

        void readManifest() throws IOException {
            ZipEntry first = zip.getNextEntry();
            if (first == null || !MANIFEST.equals(first.getName())) {
                throw new IOException(messages.get("backup.error.entryInvalid", first == null ? "(vuoto)" : first.getName()));
            }
            manifest = json.readValue(zip.readAllBytes(), BackupManifest.class);
        }

        @Override
        public boolean encrypted() {
            return encrypted;
        }

        @Override
        public BackupManifest manifest() {
            return manifest;
        }

        @Override
        public Entry next() {
            try {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    return null;
                }
                String name = entry.getName();
                if (SUMMARY.equals(name)) {
                    return new Entry(Entry.Kind.SUMMARY, name, null, json.readValue(zip.readAllBytes(), BackupSummary.class));
                }
                if (name.startsWith(BLOBS) && name.length() > BLOBS.length()) {
                    return new Entry(Entry.Kind.BLOB, name.substring(BLOBS.length()), content(), null);
                }
                if (name.startsWith(TABLES) && name.endsWith(TABLE_SUFFIX)) {
                    String table = name.substring(TABLES.length(), name.length() - TABLE_SUFFIX.length());
                    if (TABLE_NAME.matcher(table).matches()) {
                        return new Entry(Entry.Kind.TABLE, table, content(), null);
                    }
                }
                throw new BackupException(messages.get("backup.error.entryInvalid", name));
            } catch (IOException | JacksonException e) {
                throw new BackupException(messages.get("backup.error.truncated", String.valueOf(e.getMessage())), e);
            }
        }

        /** Il contenuto della voce corrente: chiuderlo non chiude lo zip, e un errore di lettura (archivio troncato o manomesso) ha un messaggio chiaro. */
        private InputStream content() {
            return new FilterInputStream(zip) {
                @Override
                public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException e) {
                        throw unreadable(e);
                    }
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    try {
                        return super.read(b, off, len);
                    } catch (IOException e) {
                        throw unreadable(e);
                    }
                }

                @Override
                public void close() {
                    // la voce si chiude passando alla successiva
                }
            };
        }

        private IOException unreadable(IOException e) {
            return new IOException(messages.get("backup.error.truncated", String.valueOf(e.getMessage())), e);
        }

        @Override
        public void close() {
            closeQuietly(zip);
        }
    }

    /** Un file come sorgente "cifrata" per {@link ChunkedAesGcmCipher} (non si riusa quella dello storage: e' un adapter di un altro sottosistema). */
    private record FileSource(Path file) implements EncryptedBlobSource {

        @Override
        public long length() throws IOException {
            return Files.size(file);
        }

        @Override
        public InputStream read(long offset, long len) throws IOException {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
            try {
                channel.position(offset);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            return new BoundedInputStream(Channels.newInputStream(channel), len);
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Chiusura", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Impossibile eliminare il file temporaneo {}", file, e);
        }
    }
}
