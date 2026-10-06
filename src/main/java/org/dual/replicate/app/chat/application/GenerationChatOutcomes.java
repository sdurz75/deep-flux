package org.dual.replicate.app.chat.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dual.replicate.core.chat.domain.ChatOutcomeView;
import org.dual.replicate.core.chat.domain.FileRef;
import org.dual.replicate.core.chat.port.in.IChatOutcomeResolver;
import org.dual.replicate.app.generation.domain.Generation;
import org.dual.replicate.app.generation.domain.GenerationStatus;
import org.dual.replicate.app.generation.port.in.IGenerations;
import org.springframework.stereotype.Component;

/**
 * Lato app dell'esito di chat: qui (e solo qui) il riferimento opaco di un turno e' l'id di una {@code Generation}. Dice alla chat come
 * presentarla, al modello come nota in inglese ("Generation #12 finished: files a.png.") e all'utente come allegati.
 */
@Component
public class GenerationChatOutcomes implements IChatOutcomeResolver {

    private final IGenerations generations;

    GenerationChatOutcomes(IGenerations generations) {
        this.generations = generations;
    }

    @Override
    public Map<Long, ChatOutcomeView> resolve(Collection<Long> refs) {
        Map<Long, ChatOutcomeView> views = new LinkedHashMap<>();
        if (refs.isEmpty()) {
            return views;
        }
        for (Generation generation : generations.findAllById(List.copyOf(refs))) {
            views.put(generation.getId(), new ChatOutcomeView(outcomeNote(generation), fileRefs(generation)));
        }
        return views;
    }

    /** L'esito di una generazione come lo deve leggere il modello: in inglese, con l'id (#n) con cui l'utente e i tool la chiamano. */
    static String outcomeNote(Generation generation) {
        String id = "Generation #" + generation.getId();
        if (generation.getStatus() == GenerationStatus.SUCCEEDED) {
            return id + " finished: files " + String.join(", ", generation.getImageFilenames()) + ".";
        }
        if (generation.getStatus() == GenerationStatus.FAILED) {
            String reason = generation.getErrorMessage();
            return id + " failed: " + (reason == null || reason.isBlank() ? "unknown error" : reason.strip());
        }
        return id + " is still in progress.";
    }

    /**
     * Una generazione puo' avere piu' di un'immagine (num_outputs > 1): tutte finiscono nella stessa bolla di chat, deep-chat le mostra
     * come piu' file nello stesso turno. {@code null} se non c'e' nulla da mostrare.
     */
    public static List<FileRef> fileRefs(Generation generation) {
        if (generation == null || generation.getImageFilenames().isEmpty()) {
            return null;
        }
        return generation.getImageFilenames().stream()
                .map(filename -> new FileRef("/images/" + filename, filename, generation.isVideo() ? "video" : "image"))
                .toList();
    }
}
