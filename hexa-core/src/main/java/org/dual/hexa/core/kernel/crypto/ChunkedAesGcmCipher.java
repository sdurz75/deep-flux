package org.dual.hexa.core.kernel.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifratura simmetrica a chunk (AES-256-GCM) dei binari su WebDAV, con accesso casuale in lettura (Range dei video).
 *
 * <p>Formato: header di 16 byte ({@code "DFX1"}, dimensione chunk in chiaro come int, prefisso nonce di 8 byte casuali
 * per file) seguito da chunk di {@code chunkSize} byte in chiaro (l'ultimo puo' essere piu' corto, anche vuoto se il
 * file e' vuoto), ciascuno cifrato con nonce = prefisso + indice del chunk (4 byte) e seguito dal tag GCM di 16 byte.
 * L'AAD di ogni chunk e' header + indice + flag "ultimo chunk": un chunk manomesso, riordinato, copiato da un altro
 * file o un file troncato/esteso fa fallire l'autenticazione (mai dati in chiaro sbagliati in silenzio).
 */
public final class ChunkedAesGcmCipher {

    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;

    private static final byte[] MAGIC = {'D', 'F', 'X', '1'};
    private static final int HEADER_LENGTH = 16;
    private static final int TAG_LENGTH = 16;
    private static final int NONCE_PREFIX_LENGTH = 8;

    private final SecretKeySpec key;
    private final int chunkSize;
    private final SecureRandom random = new SecureRandom();

    public ChunkedAesGcmCipher(byte[] key) {
        this(key, DEFAULT_CHUNK_SIZE);
    }

    /** {@code chunkSize} configurabile per i test (chunk piccoli per esercitare i confini). */
    ChunkedAesGcmCipher(byte[] key, int chunkSize) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("La chiave di cifratura deve essere di 32 byte (AES-256)");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize");
        }
        this.key = new SecretKeySpec(key, "AES");
        this.chunkSize = chunkSize;
    }

    /** Chiave AES-256 da base64 (es. {@code openssl rand -base64 32}). */
    public static byte[] keyFromBase64(String base64) {
        try {
            return Base64.getDecoder().decode(base64 == null ? "" : base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La chiave di cifratura non e' base64 valido", e);
        }
    }

    /**
     * Cifra un valore piccolo in memoria (es. un token API: sta in un solo chunk) con lo STESSO formato dei binari: usato da
     * {@code SecretCipher} per i segreti nel DB, con la stessa chiave dei binari WebDAV.
     */
    public byte[] encryptBytes(byte[] plain) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(plain.length + HEADER_LENGTH + TAG_LENGTH);
        try {
            encrypt(new java.io.ByteArrayInputStream(plain), out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e); // flussi in memoria: non puo' accadere
        }
        return out.toByteArray();
    }

    /** Inverso di {@link #encryptBytes}: {@link IOException} se il blob e' manomesso, troncato o cifrato con un'altra chiave. */
    public byte[] decryptBytes(byte[] blob) throws IOException {
        EncryptedBlobSource source = new EncryptedBlobSource() {
            @Override
            public long length() {
                return blob.length;
            }

            @Override
            public InputStream read(long offset, long len) {
                int from = (int) Math.min(offset, blob.length);
                return new java.io.ByteArrayInputStream(blob, from, (int) Math.min(len, blob.length - from));
            }
        };
        try (InputStream in = decryptRange(source, 0, plainSize(source))) {
            return in.readAllBytes();
        }
    }

    /** Legge {@code in} fino alla fine e scrive su {@code out} il blob cifrato. Non chiude nessuno dei due. */
    public void encrypt(InputStream in, OutputStream out) throws IOException {
        byte[] header = newHeader();
        out.write(header);
        byte[] current = in.readNBytes(chunkSize);
        long index = 0;
        while (true) {
            // Un chunk pieno puo' essere l'ultimo solo se dopo di lui non c'e' altro: serve guardare avanti.
            byte[] next = current.length == chunkSize ? in.readNBytes(chunkSize) : new byte[0];
            boolean last = next.length == 0;
            out.write(crypt(Cipher.ENCRYPT_MODE, header, index, last, current));
            if (last) {
                return;
            }
            current = next;
            index++;
        }
    }

    /**
     * Versione "a spinta" di {@link #encrypt}: cio' che si scrive nello stream esce cifrato su {@code out} nello STESSO formato
     * (si legge con {@link #decryptRange}), per chi produce i dati mentre li scrive (es. uno zip). Un chunk pieno si emette solo quando
     * arriva un altro byte, perche' l'ultimo chunk e' marcato nell'AAD: {@code close} scrive l'ultimo (anche vuoto, se non e' stato scritto
     * nulla) e CHIUDE {@code out}. Senza {@code close} il blob risulta troncato e non si autentica.
     */
    public OutputStream encryptingStream(OutputStream out) throws IOException {
        byte[] header = newHeader();
        out.write(header);
        return new EncryptingStream(out, header);
    }

    /** Dimensione in chiaro del blob (legge solo l'header). */
    public long plainSize(EncryptedBlobSource source) throws IOException {
        long total = source.length();
        Layout layout = layout(total);
        return (layout.chunks - 1) * (long) chunkSize + (layout.lastEncrypted - TAG_LENGTH);
    }

    /**
     * {@code length} byte in chiaro a partire da {@code offset} (meno, se il blob finisce prima), decifrati e
     * autenticati chunk per chunk. Un errore di autenticazione arriva come {@link IOException} durante la lettura.
     */
    public InputStream decryptRange(EncryptedBlobSource source, long offset, long length) throws IOException {
        long total = source.length();
        byte[] header = readHeader(source);
        Layout layout = layout(total);
        long plainSize = (layout.chunks - 1) * (long) chunkSize + (layout.lastEncrypted - TAG_LENGTH);
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("range");
        }
        long end = Math.min(plainSize, offset + length);
        if (offset >= end) {
            return InputStream.nullInputStream();
        }
        long firstChunk = offset / chunkSize;
        long lastChunk = (end - 1) / chunkSize;
        long encryptedStart = HEADER_LENGTH + firstChunk * (chunkSize + TAG_LENGTH);
        long encryptedEnd = Math.min(total, HEADER_LENGTH + (lastChunk + 1) * (chunkSize + TAG_LENGTH));
        InputStream encrypted = source.read(encryptedStart, encryptedEnd - encryptedStart);
        return new DecryptingStream(encrypted, header, layout.chunks, firstChunk, offset - firstChunk * chunkSize,
                end - offset);
    }

    private byte[] newHeader() {
        byte[] prefix = new byte[NONCE_PREFIX_LENGTH];
        random.nextBytes(prefix);
        return ByteBuffer.allocate(HEADER_LENGTH).put(MAGIC).putInt(chunkSize).put(prefix).array();
    }

    private byte[] readHeader(EncryptedBlobSource source) throws IOException {
        try (InputStream in = source.read(0, HEADER_LENGTH)) {
            byte[] header = in.readNBytes(HEADER_LENGTH);
            if (header.length != HEADER_LENGTH || !Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC)
                    || ByteBuffer.wrap(header, MAGIC.length, 4).getInt() != chunkSize) {
                throw new IOException("Blob cifrato non valido (header)");
            }
            return header;
        }
    }

    /** Numero di chunk e dimensione cifrata dell'ultimo, dalla sola lunghezza totale. */
    private Layout layout(long total) throws IOException {
        long body = total - HEADER_LENGTH;
        long encryptedChunk = chunkSize + TAG_LENGTH;
        if (body < TAG_LENGTH) {
            throw new IOException("Blob cifrato non valido (troppo corto)");
        }
        long chunks = (body + encryptedChunk - 1) / encryptedChunk;
        return new Layout(chunks, body - (chunks - 1) * encryptedChunk);
    }

    private record Layout(long chunks, long lastEncrypted) {
    }

    private byte[] crypt(int mode, byte[] header, long index, boolean last, byte[] data) throws IOException {
        try {
            byte[] nonce = ByteBuffer.allocate(12).put(header, MAGIC.length + 4, NONCE_PREFIX_LENGTH)
                    .putInt((int) index).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce));
            cipher.updateAAD(ByteBuffer.allocate(HEADER_LENGTH + 5).put(header).putInt((int) index)
                    .put((byte) (last ? 1 : 0)).array());
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cifratura/decifratura fallita (dati manomessi o chiave errata)", e);
        }
    }

    private final class EncryptingStream extends OutputStream {

        private final OutputStream out;
        private final byte[] header;
        private final byte[] buffer = new byte[chunkSize];
        private int count;
        private long index;
        private boolean closed;

        EncryptingStream(OutputStream out, byte[] header) {
            this.out = out;
            this.header = header;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream chiuso");
            }
            java.util.Objects.checkFromIndexSize(off, len, b.length);
            int pos = off;
            int end = off + len;
            while (pos < end) {
                if (count == chunkSize) {
                    // C'e' altro dopo un chunk pieno: non e' l'ultimo.
                    out.write(crypt(Cipher.ENCRYPT_MODE, header, index++, false, buffer));
                    count = 0;
                }
                int n = Math.min(chunkSize - count, end - pos);
                System.arraycopy(b, pos, buffer, count, n);
                count += n;
                pos += n;
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.write(crypt(Cipher.ENCRYPT_MODE, header, index, true, Arrays.copyOf(buffer, count)));
            } finally {
                out.close();
            }
        }
    }

    private final class DecryptingStream extends InputStream {

        private final InputStream encrypted;
        private final byte[] header;
        private final long chunks;
        private long index;
        private long skip;
        private long remaining;
        private byte[] plain = new byte[0];
        private int position;

        DecryptingStream(InputStream encrypted, byte[] header, long chunks, long firstChunk, long skip, long remaining) {
            this.encrypted = encrypted;
            this.header = header;
            this.chunks = chunks;
            this.index = firstChunk;
            this.skip = skip;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            while (position >= plain.length) {
                nextChunk();
            }
            int n = (int) Math.min(Math.min(len, plain.length - position), remaining);
            System.arraycopy(plain, position, b, off, n);
            position += n;
            remaining -= n;
            return n;
        }

        private void nextChunk() throws IOException {
            byte[] block = encrypted.readNBytes(chunkSize + TAG_LENGTH);
            if (block.length == 0) {
                throw new IOException("Blob cifrato troncato");
            }
            plain = crypt(Cipher.DECRYPT_MODE, header, index, index == chunks - 1, block);
            index++;
            position = (int) Math.min(skip, plain.length);
            skip = 0;
        }

        @Override
        public void close() throws IOException {
            encrypted.close();
        }
    }
}
