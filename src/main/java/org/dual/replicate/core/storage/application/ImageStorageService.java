package org.dual.replicate.core.storage.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.Base64;
import java.util.OptionalLong;

import org.dual.replicate.core.kernel.i18n.Messages;
import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.storage.domain.SourceImage;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.domain.StorageNames;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.dual.replicate.core.storage.port.in.IImageStorageService;
import org.dual.replicate.core.storage.port.out.IBlobBackend;
import org.dual.replicate.core.storage.port.out.IRemoteFileFetcher;
import org.springframework.stereotype.Service;

/**
 * Use case dello storage dei binari: download da URL Replicate, nomi dei file, validazione degli upload dai magic bytes,
 * data-URI, confinamento dei filename. La persistenza vera e' dietro {@link IBlobBackend} (scelto da {@code storage.type}),
 * il download dietro {@link IRemoteFileFetcher}.
 */
@Service
public class ImageStorageService implements IImageStorageService {

    private final IBlobBackend backend;
    private final IRemoteFileFetcher fetcher;
    private final Messages messages;

    public ImageStorageService(IBlobBackend backend, IRemoteFileFetcher fetcher, Messages messages) {
        this.backend = backend;
        this.fetcher = fetcher;
        this.messages = messages;
    }

    @Override
    public String downloadAndStore(String sourceUrl) {
        String filename = StorageNames.newFilename(extensionFrom(sourceUrl));
        // Un blip di rete durante il download di una prediction RIUSCITA la farebbe fallire (gli URL di output scadono):
        // il fetcher ritenta l'intero download+scrittura (idempotente: stesso nome, scrittura atomica). Se il backend ritenta gia'
        // da se' (WebDAV) i tentativi si moltiplicano (fino a 3x3): accettato, e' un caso sporadico e l'esito peggiore e'
        // solo qualche richiesta in piu' prima di fallire.
        fetcher.fetch(sourceUrl, filename, body -> backend.write(filename, body));
        return filename;
    }

    /**
     * Il nome del file e' interamente generato qui (nessun path traversal dal nome originale dell'upload), come per
     * ogni altro binario: vedi {@link StorageNames#newFilename}.
     */
    @Override
    public String storeUpload(UploadedFile upload) {
        SourceUpload checked = checkUpload(upload);
        String filename = StorageNames.newFilename(checked.extension());
        try {
            backend.write(filename, new ByteArrayInputStream(checked.bytes()));
            return filename;
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.saveImage", upload.originalFilename()), e, Kind.PERMANENT);
        }
    }

    @Override
    public void restore(String filename, InputStream content) {
        StorageNames.checkFilename(filename);
        try {
            backend.write(filename, content);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.saveImage", filename), e, Kind.PERMANENT);
        }
    }

    @Override
    public SourceImage inspectUpload(UploadedFile upload) {
        SourceUpload checked = checkUpload(upload);
        return new SourceImage(checked.bytes(), IImageStorageService.mimeOf("x." + checked.extension()));
    }

    @Override
    public SourceImage read(String filename) {
        return new SourceImage(readAllBytes(filename), IImageStorageService.mimeOf(filename));
    }

    @Override
    public String readAsDataUri(String filename) {
        return "data:" + IImageStorageService.mimeOf(filename) + ";base64,"
                + Base64.getEncoder().encodeToString(readAllBytes(filename));
    }

    @Override
    public void delete(String filename) {
        if (filename == null) {
            return;
        }
        StorageNames.checkFilename(filename);
        try {
            backend.remove(filename);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.deleteImage", filename), e, Kind.PERMANENT);
        }
    }

    @Override
    public OptionalLong size(String filename) {
        StorageNames.checkFilename(filename);
        return backend.size(filename);
    }

    @Override
    public InputStream openRange(String filename, long offset, long length) throws IOException {
        StorageNames.checkFilename(filename);
        return backend.openRange(filename, offset, length);
    }

    private byte[] readAllBytes(String filename) {
        StorageNames.checkFilename(filename);
        try {
            OptionalLong size = backend.size(filename);
            if (size.isEmpty()) {
                throw new NoSuchFileException(filename);
            }
            try (InputStream in = backend.openRange(filename, 0, size.getAsLong())) {
                return in.readAllBytes();
            }
        } catch (NoSuchFileException e) {
            // Atteso (riga senza file, file gia' cancellato): un esito, non un guasto dello storage.
            throw new StorageException(messages.get("imagestorage.error.readImage", filename), e, Kind.REJECTED);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.readImage", filename), e, Kind.PERMANENT);
        }
    }

    private SourceUpload checkUpload(UploadedFile upload) {
        if (upload.size() > MAX_UPLOAD_BYTES) {
            throw new StorageException(messages.get("imagestorage.error.uploadTooLarge", MAX_UPLOAD_BYTES / (1024 * 1024)), null, Kind.REJECTED);
        }
        try (InputStream content = upload.content().open()) {
            byte[] bytes = content.readNBytes((int) MAX_UPLOAD_BYTES + 1);
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw new StorageException(messages.get("imagestorage.error.uploadTooLarge", MAX_UPLOAD_BYTES / (1024 * 1024)), null, Kind.REJECTED);
            }
            String extension = imageExtensionOf(bytes);
            if (extension == null) {
                throw new StorageException(messages.get("imagestorage.error.uploadInvalidType"), null, Kind.REJECTED);
            }
            return new SourceUpload(bytes, extension);
        } catch (IOException e) {
            throw new StorageException(messages.get("imagestorage.error.readImage", upload.originalFilename()), e, Kind.PERMANENT);
        }
    }

    private record SourceUpload(byte[] bytes, String extension) {
    }

    private static String imageExtensionOf(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static String extensionFrom(String url) {
        String path = URI.create(url).getPath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1);
            if (ext.length() <= 5) {
                return ext;
            }
        }
        return "png";
    }
}
