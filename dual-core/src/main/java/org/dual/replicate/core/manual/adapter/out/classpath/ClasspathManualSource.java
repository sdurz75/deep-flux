package org.dual.replicate.core.manual.adapter.out.classpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.dual.replicate.core.manual.domain.ManualDocument;
import org.dual.replicate.core.manual.domain.ManualException;
import org.dual.replicate.core.manual.domain.ManualLinks;
import org.dual.replicate.core.manual.port.out.IManualSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * I testi del manuale dal classpath (quindi dal jar): {@code manual/<lingua>/<NN-gruppo>/<NN-pagina>.md}. L'ordine e' quello dei percorsi (i prefissi
 * numerici a due cifre), gruppo e slug sono i nomi senza prefisso. Una lingua senza cartella ricade sull'italiano: aggiungere {@code manual/en/} basta
 * a offrire il manuale in inglese, senza codice.
 */
@Component
public class ClasspathManualSource implements IManualSource {

    static final String DEFAULT_LANGUAGE = "it";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Override
    public List<ManualDocument> documents(String language) {
        List<ManualDocument> documents = load(language);
        return documents.isEmpty() && !DEFAULT_LANGUAGE.equals(language) ? load(DEFAULT_LANGUAGE) : documents;
    }

    private List<ManualDocument> load(String language) {
        String marker = "/manual/" + language + "/";
        try {
            record Found(String relativePath, Resource resource) {
            }
            List<Found> found = new ArrayList<>();
            for (Resource resource : resolver.getResources("classpath*:manual/" + language + "/**/*.md")) {
                String url = resource.getURL().toString();
                int at = url.lastIndexOf(marker);
                if (at >= 0) {
                    found.add(new Found(url.substring(at + marker.length()), resource));
                }
            }
            found.sort(Comparator.comparing(Found::relativePath));
            List<ManualDocument> documents = new ArrayList<>();
            for (Found f : found) {
                String[] parts = f.relativePath().split("/");
                String file = parts[parts.length - 1];
                String group = parts.length > 1 ? ManualLinks.withoutOrder(parts[parts.length - 2]) : "";
                String slug = ManualLinks.withoutOrder(file.substring(0, file.length() - ".md".length()));
                String markdown = new String(f.resource().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                documents.add(new ManualDocument(group, slug, markdown));
            }
            return documents;
        } catch (IOException e) {
            throw new ManualException("Impossibile leggere il manuale (" + language + ")", e);
        }
    }
}
