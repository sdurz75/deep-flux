package org.dual.replicate.core.storage.adapter.in.web;

import java.io.IOException;

import org.dual.replicate.core.kernel.remote.RemoteServiceException.Kind;
import org.dual.replicate.core.storage.domain.StorageException;
import org.dual.replicate.core.storage.domain.UploadedFile;
import org.springframework.web.multipart.MultipartFile;

/** Traduce un {@code MultipartFile} nel tipo di dominio {@link UploadedFile} (lo storage non conosce il framework web). */
public final class UploadedFiles {

    private UploadedFiles() {
    }

    public static UploadedFile of(MultipartFile file) {
        try {
            return new UploadedFile(file.getOriginalFilename(), file.getSize(), file.getInputStream());
        } catch (IOException e) {
            throw new StorageException("Upload non leggibile: " + file.getOriginalFilename(), e, Kind.PERMANENT);
        }
    }
}
