package org.dual.replicate.replicate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Catalogo dei modelli Replicate text-to-image, precaricato una volta
 * all'avvio (non ad ogni richiesta) da GET /collections/{slug} e tenuto
 * in memoria: alimenta la dropdown di /deep-chat, cosi' l'utente scegli
 * un modello valido invece di digitarlo a mano.
 *
 * Se il fetch fallisce (token assente, Replicate irraggiungibile) l'app
 * parte comunque con un catalogo vuoto: a differenza dello schema DB
 * (Flyway, fail-fast) questo e' un dato accessorio, non un invariante
 * dell'applicazione.
 */
@Component
public class ReplicateModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ReplicateModelCatalog.class);

    private final ReplicateClient replicateClient;
    private final String collectionSlug;

    private volatile List<ReplicateModelSummary> models = List.of();

    public ReplicateModelCatalog(ReplicateClient replicateClient,
                                  @Value("${replicate.model-collection}") String collectionSlug) {
        this.replicateClient = replicateClient;
        this.collectionSlug = collectionSlug;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        try {
            CollectionResponse collection = replicateClient.getCollection(collectionSlug);
            models = collection.models().stream()
                    .sorted(Comparator.comparing(ReplicateModelSummary::id))
                    .toList();
            log.info("Caricati {} modelli Replicate dalla collection \"{}\"", models.size(), collectionSlug);
        } catch (Exception e) {
            log.warn("Impossibile precaricare i modelli Replicate dalla collection \"{}\": {}", collectionSlug, e.getMessage());
        }
    }

    public List<ReplicateModelSummary> models() {
        return models;
    }

    public boolean contains(String id) {
        return models.stream().anyMatch(m -> m.id().equals(id));
    }

    public String idsAsCsv() {
        return models.stream().map(ReplicateModelSummary::id).collect(Collectors.joining(", "));
    }
}
