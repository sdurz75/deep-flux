package org.dual.hexa.app.training.adapter.out.replicate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Un archivio tar (ustar) minimo per i test: nessuna libreria, il formato e' un'intestazione da 512 byte e il contenuto a blocchi di 512. */
final class TarBuilder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    TarBuilder directory(String name) {
        out.writeBytes(header(name, 0, '5', null));
        return this;
    }

    TarBuilder file(String name, byte[] content) {
        out.writeBytes(header(name, content.length, '0', null));
        pad(content);
        return this;
    }

    /** Una voce GNU "nome lungo" seguita dal file vero (il cui nome nell'intestazione e' troncato). */
    TarBuilder fileWithLongName(String longName, byte[] content) {
        byte[] nameBytes = (longName + "\0").getBytes(StandardCharsets.UTF_8);
        out.writeBytes(header("././@LongLink", nameBytes.length, 'L', null));
        pad(nameBytes);
        out.writeBytes(header(longName.substring(0, 50), content.length, '0', null));
        pad(content);
        return this;
    }

    /** Un file col nome diviso fra prefisso e nome, come fa ustar per i percorsi oltre i 100 caratteri. */
    TarBuilder fileWithPrefix(String prefix, String name, byte[] content) {
        out.writeBytes(header(name, content.length, '0', prefix));
        pad(content);
        return this;
    }

    byte[] build() {
        out.writeBytes(new byte[1024]); // due blocchi a zero chiudono l'archivio
        return out.toByteArray();
    }

    private void pad(byte[] content) {
        out.writeBytes(content);
        out.writeBytes(new byte[(512 - content.length % 512) % 512]);
    }

    /** L'intestazione di un file regolare: per i test che generano il contenuto al volo invece di tenerlo in memoria. */
    static byte[] fileHeader(String name, long size) {
        return header(name, size, '0', null);
    }

    static byte[] directoryHeader(String name) {
        return header(name, 0, '5', null);
    }

    private static byte[] header(String name, long size, char type, String prefix) {
        byte[] header = new byte[512];
        put(header, 0, name, 100);
        put(header, 100, "0000644", 8);
        put(header, 124, "%011o".formatted(size), 12);
        header[156] = (byte) type;
        put(header, 257, "ustar", 6);
        if (prefix != null) {
            put(header, 345, prefix, 155);
        }
        return header;
    }

    private static void put(byte[] header, int offset, String text, int max) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, max));
    }
}
