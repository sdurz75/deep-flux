package org.dual.replicate.app.chat.domain;

import java.util.List;

import org.dual.replicate.app.generation.domain.Generation;

/**
 * Vocabolario JSON di deep-chat per un allegato: {@code type} e' "image" (il solo che deep-chat riconosca per mostrare un'immagine
 * in chat invece di un link) o "video". Lo usano la risposta di una pagina con la cronologia ricostruita e il payload del push
 * asincrono ({@code ChatMessagePushEvent}).
 */
public record FileRef(String src, String name, String type) {

    /**
     * Una generazione puo' avere piu' di un'immagine (num_outputs > 1): tutte finiscono nella stessa bolla di chat, deep-chat le mostra
     * come piu' file nello stesso turno. {@code null} se non c'e' nulla da mostrare.
     */
    public static List<FileRef> of(Generation generation) {
        if (generation == null || generation.getImageFilenames().isEmpty()) {
            return null;
        }
        return generation.getImageFilenames().stream()
                .map(filename -> new FileRef("/images/" + filename, filename, generation.isVideo() ? "video" : "image"))
                .toList();
    }
}
