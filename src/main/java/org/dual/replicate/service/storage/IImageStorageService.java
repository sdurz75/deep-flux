package org.dual.replicate.service.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalLong;

import org.dual.replicate.service.PromptEnhancementService;
import org.springframework.web.multipart.MultipartFile;

/**
 * Persistenza dei binari dell'app (immagini, video mp4, upload sorgente): l'unico punto da cui li si scrive, li si
 * legge e li si serve (vedi ImageController per {@code /images/**}). Implementazioni alternative, scelte da
 * {@code storage.type}: {@link LocalFsImageStorageService} (filesystem locale) e {@link WebDavImageStorageService}
 * (WebDAV, contenuti cifrati con chiave simmetrica).
 *
 * <p>I filename sono generati dall'app ({@code <sha256-casuale>.<ext>}; i file storici possono avere altri nomi: il filename e' opaco per l'app) e vivono
 * a un solo livello logico (fisicamente annidati per hash dal backend): qualunque implementazione rifiuta separatori e {@code ..} con {@link IllegalArgumentException}.
 */
public interface IImageStorageService {

    /** Dimensione massima di un'immagine caricata dall'utente (vedi anche spring.servlet.multipart). */
    long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /**
     * Scarica {@code sourceUrl} e salva il risultato con un nome nuovo, {@code "<sha256-casuale>.<ext>"}; ritorna il
     * filename. Il nome non dipende da id o dall'URL: mai collisioni, nemmeno dopo un reset del DB. Un download
     * fallito non lascia mai un file col nome definitivo.
     */
    String downloadAndStore(String sourceUrl);

    /**
     * Salva un'immagine caricata dall'utente con un nome nuovo ({@code "<sha256-casuale>.<ext>"}). Il tipo si deduce dai magic bytes
     * (png/jpeg/webp), mai dal content-type o dal nome originale, che il client controlla.
     */
    String storeUpload(MultipartFile upload);

    /** Valida un upload SENZA salvarlo (AI enhance): stessi controlli di {@link #storeUpload}. */
    PromptEnhancementService.SourceImage inspectUpload(MultipartFile upload);

    /** Legge {@code filename} come immagine sorgente (byte + mime dedotto dall'estensione). */
    PromptEnhancementService.SourceImage read(String filename);

    /** Legge {@code filename} come data-URI: il modo di passare a Replicate un'immagine locale senza URL pubblico. */
    String readAsDataUri(String filename);

    /** Elimina {@code filename}, se presente. No-op se {@code filename} e' null. */
    void delete(String filename);

    /** Dimensione in chiaro di {@code filename}; vuoto se non esiste. */
    OptionalLong size(String filename);

    /**
     * Apre {@code length} byte in chiaro di {@code filename} a partire da {@code offset} (per servire i Range HTTP
     * dei video). Chi riceve lo stream lo chiude. {@link java.nio.file.NoSuchFileException} se il file non esiste.
     */
    InputStream openRange(String filename, long offset, long length) throws IOException;

    /** Tipo MIME dedotto dall'estensione di {@code filename} (png di default). */
    static String mimeOf(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            default -> "image/png";
        };
    }
}
