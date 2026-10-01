package org.dual.replicate.core.kernel.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkedAesGcmCipherTest {

    private static final int CHUNK = 16;

    private final byte[] keyBytes = new byte[32];
    private final ChunkedAesGcmCipher cipher;

    ChunkedAesGcmCipherTest() {
        new Random(1).nextBytes(keyBytes);
        cipher = new ChunkedAesGcmCipher(keyBytes, CHUNK);
    }

    private static byte[] data(int length) {
        byte[] b = new byte[length];
        new Random(length).nextBytes(b);
        return b;
    }

    private byte[] encrypt(byte[] plain) throws IOException {
        return encrypt(cipher, plain);
    }

    private static byte[] encrypt(ChunkedAesGcmCipher c, byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        c.encrypt(new ByteArrayInputStream(plain), out);
        return out.toByteArray();
    }

    private static EncryptedBlobSource source(byte[] blob) {
        return new EncryptedBlobSource() {
            @Override
            public long length() {
                return blob.length;
            }

            @Override
            public InputStream read(long offset, long len) {
                int from = (int) Math.min(offset, blob.length);
                int to = (int) Math.min(blob.length, offset + len);
                return new ByteArrayInputStream(blob, from, to - from);
            }
        };
    }

    private byte[] decrypt(byte[] blob, long offset, long length) throws IOException {
        try (InputStream in = cipher.decryptRange(source(blob), offset, length)) {
            return in.readAllBytes();
        }
    }

    @Test
    void roundTripsAroundChunkBoundaries() throws IOException {
        for (int length : new int[] {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK, 3 * CHUNK + 5}) {
            byte[] plain = data(length);
            byte[] blob = encrypt(plain);

            assertThat(decrypt(blob, 0, length)).as("length %d", length).isEqualTo(plain);
            assertThat(cipher.plainSize(source(blob))).as("size %d", length).isEqualTo(length);
        }
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() throws IOException {
        byte[] plain = "PLAINTEXT-MARKER-PLAINTEXT-MARKER-PLAINTEXT-MARKER".getBytes();

        assertThat(new String(encrypt(plain), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("MARKER");
    }

    @Test
    void decryptsAnyRangeSpanningChunks() throws IOException {
        byte[] plain = data(5 * CHUNK + 3);
        byte[] blob = encrypt(plain);

        for (long[] range : new long[][] {{0, 1}, {CHUNK - 1, 2}, {CHUNK, CHUNK}, {CHUNK + 3, 3 * CHUNK}, {plain.length - 1, 1},
                {2 * CHUNK + 5, 1000}}) {
            int from = (int) range[0];
            int to = (int) Math.min(plain.length, range[0] + range[1]);
            assertThat(decrypt(blob, range[0], range[1])).as("range %s", Arrays.toString(range))
                    .isEqualTo(Arrays.copyOfRange(plain, from, to));
        }
        assertThat(decrypt(blob, plain.length, 10)).isEmpty();
    }

    @Test
    void twoEncryptionsOfTheSameDataDiffer() throws IOException {
        byte[] plain = data(40);

        assertThat(encrypt(plain)).isNotEqualTo(encrypt(plain));
    }

    @Test
    void aTamperedChunkFailsAuthentication() throws IOException {
        byte[] blob = encrypt(data(3 * CHUNK));
        blob[16 + CHUNK + 16 + 2] ^= 0x01; // secondo chunk

        assertThatThrownBy(() -> decrypt(blob, 0, 3 * CHUNK)).isInstanceOf(IOException.class);
        assertThat(decrypt(blob, 0, CHUNK)).hasSize(CHUNK); // il primo chunk e' integro
    }

    @Test
    void reorderedChunksFailAuthentication() throws IOException {
        byte[] blob = encrypt(data(3 * CHUNK));
        int e = CHUNK + 16;
        byte[] swapped = blob.clone();
        System.arraycopy(blob, 16 + e, swapped, 16, e);
        System.arraycopy(blob, 16, swapped, 16 + e, e);

        assertThatThrownBy(() -> decrypt(swapped, 0, 3 * CHUNK)).isInstanceOf(IOException.class);
    }

    @Test
    void aTruncatedBlobFailsInsteadOfReturningShortData() throws IOException {
        byte[] blob = encrypt(data(3 * CHUNK + 4));
        byte[] truncated = Arrays.copyOf(blob, blob.length - (4 + 16)); // toglie l'ultimo chunk (parziale)

        assertThatThrownBy(() -> decrypt(truncated, 0, 3 * CHUNK + 4)).isInstanceOf(IOException.class);
    }

    @Test
    void aTamperedHeaderFailsAuthentication() throws IOException {
        byte[] blob = encrypt(data(2 * CHUNK));
        blob[10] ^= 0x01; // prefisso del nonce

        assertThatThrownBy(() -> decrypt(blob, 0, 2 * CHUNK)).isInstanceOf(IOException.class);
    }

    @Test
    void aWrongKeyFailsAuthentication() throws IOException {
        byte[] blob = encrypt(data(2 * CHUNK));
        byte[] other = keyBytes.clone();
        other[0] ^= 0x01;
        ChunkedAesGcmCipher wrong = new ChunkedAesGcmCipher(other, CHUNK);

        assertThatThrownBy(() -> wrong.decryptRange(source(blob), 0, 2 * CHUNK).readAllBytes())
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsBlobsThatAreNotEncryptedByThisFormat() {
        assertThatThrownBy(() -> cipher.decryptRange(source("not encrypted at all, plain text".getBytes()), 0, 5))
                .isInstanceOf(IOException.class);
    }

    @Test
    void keyMustBe32BytesBase64() {
        assertThatThrownBy(() -> new ChunkedAesGcmCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkedAesGcmCipher.keyFromBase64("%%%")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ChunkedAesGcmCipher.keyFromBase64(java.util.Base64.getEncoder().encodeToString(keyBytes)))
                .isEqualTo(keyBytes);
    }

    /** Valori piccoli in memoria (token API nel DB): stesso formato dei binari, round-trip e manomissione. */
    @Test
    void encryptBytesAndDecryptBytesRoundTripSmallValues() throws IOException {
        ChunkedAesGcmCipher small = new ChunkedAesGcmCipher(keyBytes);

        for (int length : new int[] {0, 1, 40, 500}) {
            byte[] plain = data(length);
            byte[] blob = small.encryptBytes(plain);

            assertThat(blob.length).isEqualTo(16 + length + 16);
            assertThat(small.decryptBytes(blob)).isEqualTo(plain);
        }
    }

    @Test
    void decryptBytesRejectsTamperedTruncatedOrForeignBlobs() {
        ChunkedAesGcmCipher small = new ChunkedAesGcmCipher(keyBytes);
        byte[] blob = small.encryptBytes(data(40));
        byte[] tampered = blob.clone();
        tampered[20] ^= 1;

        assertThatThrownBy(() -> small.decryptBytes(tampered)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> small.decryptBytes(Arrays.copyOf(blob, blob.length - 3))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new ChunkedAesGcmCipher(new byte[32]).decryptBytes(blob)).isInstanceOf(IOException.class);
    }
}
