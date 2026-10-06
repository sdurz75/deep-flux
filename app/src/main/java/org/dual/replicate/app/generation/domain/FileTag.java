package org.dual.replicate.app.generation.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Un tag utente su UN file (immagine/video) di una Generation: riga di {@code generation_file_tag}. */
@Embeddable
public class FileTag {

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "tag", nullable = false)
    private String tag;

    protected FileTag() {
    }

    public FileTag(String filename, String tag) {
        this.filename = filename;
        this.tag = tag;
    }

    public String getFilename() {
        return filename;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileTag other && Objects.equals(filename, other.filename) && Objects.equals(tag, other.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, tag);
    }
}
