package org.hexa.app.training.adapter.out.replicate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Cerca in un archivio tar (come lo restituisce Replicate: {@code flux-lora.tar}, una cartella e dentro il {@code .safetensors}) il primo file regolare con un dato
 * suffisso, leggendo SOLO le intestazioni da 512 byte e saltando il contenuto per aritmetica: con una sorgente a richieste Range sono due letture da 512 byte per
 * l'archivio vero, non il download (verificato: chiudere una risposta Reactor a meta' ne scarica comunque il resto). Capisce ustar (con prefisso), GNU
 * {@code L} (nome lungo) e la dimensione in base 256; altri tipi di voce si saltano. Niente libreria: il formato e' una pagina di specifica e serve una sola cosa.
 */
final class TarScanner {

    private static final int BLOCK = 512;
    private static final int MAX_LONG_NAME = 4096;

    /** Legge {@code length} byte da {@code offset}; meno se l'archivio finisce prima. Un errore di lettura e' una {@code IOException} o una eccezione remota. */
    @FunctionalInterface
    interface Source {

        byte[] read(long offset, int length) throws IOException;
    }

    /** Il file trovato: il nome (senza cartelle), la dimensione e dove comincia il suo contenuto, in byte dall'inizio dell'archivio. */
    record Entry(String name, long size, long dataOffset) {
    }

    private TarScanner() {
    }

    static Optional<Entry> find(Source source, String suffix) throws IOException {
        String wanted = suffix.toLowerCase(Locale.ROOT);
        long offset = 0;
        String longName = null;
        while (true) {
            byte[] header = source.read(offset, BLOCK);
            if (header.length < BLOCK || isZero(header)) {
                return Optional.empty(); // fine dell'archivio (blocchi a zero) o troncato
            }
            long size = sizeOf(header);
            long padded = (size + BLOCK - 1) / BLOCK * BLOCK;
            long dataOffset = offset + BLOCK;
            byte type = header[156];
            if (type == 'L') {
                if (size > MAX_LONG_NAME) {
                    throw new IOException("nome lungo di " + size + " byte nell'archivio");
                }
                byte[] data = source.read(dataOffset, (int) size);
                longName = cString(data, 0, data.length);
                offset = dataOffset + padded;
                continue;
            }
            String name = longName != null ? longName : nameOf(header);
            longName = null;
            boolean regular = type == '0' || type == 0;
            if (regular && name.toLowerCase(Locale.ROOT).endsWith(wanted)) {
                return Optional.of(new Entry(name.substring(name.lastIndexOf('/') + 1), size, dataOffset));
            }
            offset = dataOffset + padded;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String nameOf(byte[] header) {
        String name = cString(header, 0, 100);
        boolean ustar = "ustar".equals(new String(header, 257, 5, StandardCharsets.US_ASCII));
        String prefix = ustar ? cString(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String cString(byte[] bytes, int from, int length) {
        int end = from;
        while (end < from + length && end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, from, end - from, StandardCharsets.UTF_8);
    }

    /** Ottale in ASCII, oppure (bit alto del primo byte) intero a 12 byte in base 256, per i file oltre gli 8 GB. */
    private static long sizeOf(byte[] header) {
        if ((header[124] & 0x80) != 0) {
            long value = header[124] & 0x7F;
            for (int i = 125; i < 136; i++) {
                value = (value << 8) | (header[i] & 0xFF);
            }
            return value;
        }
        String octal = cString(header, 124, 12).strip();
        return octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
    }
}
