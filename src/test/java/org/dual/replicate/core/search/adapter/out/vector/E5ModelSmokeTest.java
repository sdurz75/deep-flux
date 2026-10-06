package org.dual.replicate.core.search.adapter.out.vector;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova REALE del modello di embedding (scarica ~135 MB in ./data/models al primo giro): opt-in con
 * {@code -Dsemantic.model.test=true}, mai in un normale {@code mvn test}.
 */
@EnabledIfSystemProperty(named = "semantic.model.test", matches = "true")
class E5ModelSmokeTest {

    static TransformersEmbeddingModel model() throws Exception {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setModelResource(System.getProperty("semantic.model.uri",
                "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx"));
        model.setTokenizerResource("https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json");
        model.setResourceCacheDirectory("./data/models");
        model.setModelOutputName("last_hidden_state");
        model.afterPropertiesSet();
        return model;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }

    @Test
    void producesSensibleItalianRankings() throws Exception {
        TransformersEmbeddingModel model = model();
        float[] query = model.embed("query: gatto");
        assertThat(query).hasSize(384);

        List<String> docs = List.of(
                "passage: un felino domestico che dorme sul divano",
                "passage: una macchina sportiva rossa su una strada di montagna",
                "passage: ritratto di una donna con i capelli lunghi al tramonto");
        double[] scores = docs.stream().mapToDouble(d -> cosine(query, model.embed(d))).toArray();
        System.out.println("SMOKE gatto -> " + java.util.Arrays.toString(scores));

        assertThat(scores[0]).isGreaterThan(scores[1]).isGreaterThan(scores[2]);
    }

    @Test
    void englishAndItalianAreComparable() throws Exception {
        TransformersEmbeddingModel model = model();
        float[] query = model.embed("query: a cat sleeping");
        double it = cosine(query, model.embed("passage: un gatto che dorme"));
        double unrelated = cosine(query, model.embed("passage: un motore diesel a sei cilindri"));
        System.out.println("SMOKE cat it/unrelated -> " + it + " / " + unrelated);
        assertThat(it).isGreaterThan(unrelated);
    }
}
