package org.dual.hexa.app.training.adapter.in.web;

import org.dual.hexa.core.storage.domain.UploadedFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dal {@code MultipartFile} di Spring al tipo di dominio dello storage (la porta non vede il framework web): l'unico punto della conversione per questo
 * sottosistema. E' una copia di quello di {@code generation}, che e' package-private: fra sottosistemi si condivide solo {@code port.in} e {@code domain}.
 */
final class UploadedFiles {

    private UploadedFiles() {
    }

    static UploadedFile of(MultipartFile file) {
        return new UploadedFile(file.getOriginalFilename(), file.getSize(), file::getInputStream);
    }
}
