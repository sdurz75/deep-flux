package org.hexa.app.generation.adapter.in.web;

import org.hexa.core.storage.domain.UploadedFile;
import org.springframework.web.multipart.MultipartFile;

/** Dal {@code MultipartFile} di Spring al tipo di dominio dello storage (la porta non vede il framework web): l'unico punto della conversione. */
final class UploadedFiles {

    private UploadedFiles() {
    }

    static UploadedFile of(MultipartFile file) {
        return new UploadedFile(file.getOriginalFilename(), file.getSize(), file::getInputStream);
    }
}
