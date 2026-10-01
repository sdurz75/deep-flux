package org.dual.replicate.app.search.adapter.out.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Embedding finto e deterministico per i test (mai il modello vero): una dimensione per "tema" (parola chiave) nelle prime 8, il resto
 * a zero fino a {@link #DIMENSIONS} (la colonna {@code vector_store.embedding} e' vector(384)), cosi' la prossimita' e' controllabile. Conta i testi embeddati per verificare che gli invariati non vengano ricalcolati.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 384;
    public static final List<String> THEMES = List.of("gatto", "felino", "auto", "montagna", "mare", "ritratto", "castello", "drago");
    public final AtomicInteger embedded = new AtomicInteger();
    public final List<String> seen = new ArrayList<>();

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return vector(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(this::vector).toList();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> result = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            result.add(new Embedding(vector(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(result);
    }

    /** "gatto" e "felino" condividono il tema 0 (sinonimi); il resto e' ortogonale. */
    private float[] vector(String text) {
        embedded.incrementAndGet();
        synchronized (seen) {
            seen.add(text);
        }
        String lower = text.toLowerCase();
        float[] v = new float[DIMENSIONS];
        for (int i = 0; i < THEMES.size(); i++) {
            if (lower.contains(THEMES.get(i))) {
                v[i == 1 ? 0 : i] += 1f;
            }
        }
        if (allZero(v)) {
            v[THEMES.size() - 1] = 0.001f; // nessun tema: quasi ortogonale a tutto
        }
        // Componente comune a tutti i vettori: due testi di temi diversi hanno similarita' piccola ma > 0. pgvector esclude con
        // soglia 0 la similarita' esattamente 0 (distanza < 1 stretta), che con vettori ortogonali farebbe sparire ogni risultato.
        v[THEMES.size()] = 0.1f;
        return v;
    }

    private static boolean allZero(float[] v) {
        for (float f : v) {
            if (f != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
