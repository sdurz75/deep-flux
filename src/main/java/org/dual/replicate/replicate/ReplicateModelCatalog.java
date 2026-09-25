package org.dual.replicate.replicate;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Catalogo dei modelli Replicate proposti nella dropdown di /deep-chat,
 * precaricato una volta all'avvio (non ad ogni richiesta) e tenuto in
 * memoria, cosi' l'utente scegli un modello valido invece di digitarlo
 * a mano. Due liste distinte:
 *
 * - {@link #models()}: la collection curata (GET /collections/{slug}).
 * - {@link #personalModels()}: modelli personali (es. account sdurz75)
 *   elencati a mano in replicate.personal-models e risolti uno per uno
 *   con GET /models/{owner}/{name}. Replicate non offre nessun endpoint
 *   per elencare i modelli di un owner (verificato: /v1/models ignora
 *   silenziosamente un parametro "owner", /v1/search e' full-text
 *   semantico e non e' un filtro affidabile) — questa lista va quindi
 *   aggiornata a mano quando si pubblica un nuovo modello personale.
 *
 * Se il fetch fallisce (token assente, Replicate irraggiungibile, uno
 * slug personale rinominato/cancellato) l'app parte comunque con quel
 * pezzo di catalogo vuoto: a differenza dello schema DB (Flyway,
 * fail-fast) questo e' un dato accessorio, non un invariante
 * dell'applicazione.
 */
@Component
public class ReplicateModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ReplicateModelCatalog.class);

    private final ReplicateClient replicateClient;
    private final String collectionSlug;
    private final List<String> personalModelSlugs;

    private volatile List<ReplicateModelSummary> models = List.of();
    private volatile List<ReplicateModelSummary> personalModels = List.of();

    public ReplicateModelCatalog(ReplicateClient replicateClient,
                                  @Value("${replicate.model-collection}") String collectionSlug,
                                  @Value("${replicate.personal-models:}") String personalModelsCsv) {
        this.replicateClient = replicateClient;
        this.collectionSlug = collectionSlug;
        this.personalModelSlugs = Arrays.stream(personalModelsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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

        personalModels = personalModelSlugs.stream()
                .map(this::resolvePersonalModel)
                .filter(m -> m != null)
                .toList();
        log.info("Caricati {} modelli personali Replicate", personalModels.size());
    }

    private ReplicateModelSummary resolvePersonalModel(String slug) {
        String[] ownerAndName = slug.split("/", 2);
        if (ownerAndName.length != 2) {
            log.warn("Slug in replicate.personal-models non nella forma \"owner/nome\": \"{}\"", slug);
            return null;
        }
        try {
            return replicateClient.getModel(ownerAndName[0], ownerAndName[1]);
        } catch (Exception e) {
            log.warn("Impossibile risolvere il modello personale \"{}\": {}", slug, e.getMessage());
            return null;
        }
    }

    public List<ReplicateModelSummary> models() {
        return models;
    }

    public List<ReplicateModelSummary> personalModels() {
        return personalModels;
    }

    public boolean contains(String id) {
        return find(id).isPresent();
    }

    /**
     * Hash della versione pubblicata piu' recente per {@code id}
     * ("owner/name"), se nota. Va sempre passata esplicitamente a
     * ReplicateClient.createPrediction: non tutti i modelli supportano
     * lo shortcut "/models/{owner}/{name}/predictions" che userebbe
     * implicitamente l'ultima versione (verificato dal vivo: 404 su
     * tutti i modelli personali account sdurz75, pur esistendo ed
     * essendo pubblici — vedi ReplicateModelSummary.latestVersionId).
     *
     * Nota su un vicolo cieco: si potrebbe pensare che pinnare la
     * versione causi il rifiuto di aspect_ratio="custom" su
     * black-forest-labs/flux-schnell/flux-dev — verificato dal vivo che
     * NON e' cosi': quei due modelli rifiutano "custom" (solo un enum
     * fisso di 11 ratio predefiniti) sia con la versione pinnata sia
     * con lo shortcut senza versione, identico errore in entrambi i
     * casi. E' semplicemente un modello che non supporta aspect_ratio
     * custom, non una questione di versione — vedi il rischio accettato
     * in proposito nel pannello impostazioni di /deep-chat.
     */
    public Optional<String> latestVersionOf(String id) {
        return find(id).map(ReplicateModelSummary::latestVersionId);
    }

    public String idsAsCsv() {
        return all().map(ReplicateModelSummary::id).collect(Collectors.joining(", "));
    }

    private Optional<ReplicateModelSummary> find(String id) {
        return all().filter(m -> m.id().equals(id)).findFirst();
    }

    private Stream<ReplicateModelSummary> all() {
        return Stream.concat(personalModels.stream(), models.stream());
    }
}
