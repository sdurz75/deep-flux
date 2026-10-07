package org.hexa.core.storage.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Regole sui nomi dei binari: generazione, confinamento del filename logico e layout fisico annidato. */
public final class StorageNames {

    private static final SecureRandom RANDOM = new SecureRandom();

    private StorageNames() {
    }

    /**
     * Nome di un nuovo binario: SHA-256 (hex) di 32 byte casuali + {@code .<extension>}. Mai derivato da id o dal
     * nome originale: e' unico anche dopo un reset del DB (gli id ripartirebbero da 1) e non rivela nulla del
     * contenuto (su WebDAV il file e' cifrato: l'estensione e' solo un'indicazione utile). Non e' un hash del
     * contenuto: ogni riga ha il suo file, quindi cancellarne una non tocca le altre.
     */
    public static String newFilename(String extension) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(sha256(random)) + "." + extension;
    }

    /**
     * Percorso fisico annidato {@code ab/cd/<filename>}, dove {@code abcd} sono i primi 2 byte (hex) dello SHA-256 del
     * filename: deterministico, quindi derivabile dal solo filename salvato nel DB. Uguale per ogni backend.
     */
    public static String shardPath(String filename) {
        checkFilename(filename);
        byte[] d = sha256(filename.getBytes(StandardCharsets.UTF_8));
        return String.format("%02x/%02x/%s", d[0] & 0xFF, d[1] & 0xFF, filename);
    }

    /**
     * Il filename logico e' a un solo livello (il layout fisico annidato lo da' {@link #shardPath}), niente traversal:
     * vale per qualunque backend (il filename arriva dal DB o dall'URL).
     */
    public static void checkFilename(String filename) {
        if (filename == null || filename.isEmpty() || filename.contains("/") || filename.contains("\\")
                || filename.contains("..")) {
            throw new IllegalArgumentException(String.valueOf(filename));
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
