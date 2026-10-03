package org.dual.replicate.app.generation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un modello Replicate censito a mano (vedi migrazione V6: nessuna UI di
 * amministrazione in questo progetto, il censimento e' un INSERT/UPDATE
 * di migrazione) e associato alla propria form di generazione
 * ({@link #formType}, vedi {@code IGenerationParameterHandler} in generation.application).
 * Sostituisce il fetch live da Replicate (GET /collections/{slug},
 * GET /models/{owner}/{name}) che alimentava la vecchia
 * ModelCatalogService: {@link #version} e' ora il valore censito qui,
 * non piu' risolto ad ogni avvio contro "l'ultima versione pubblicata".
 * Le righe si aggiungono a runtime solo per i LoRA anagrafati con sorgente {@code owner/nome} (vedi
 * {@code IModelCatalog#registerLoraFinetune}); nessuna riga si modifica.
 */
@Entity
@Table(name = "replicate_model")
public class ReplicateModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    /** Hash di versione pinnato, se noto. Null: ReplicateClient.createPrediction userebbe lo shortcut "ultima versione", che non tutti i modelli supportano. */
    private String version;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_type", nullable = false)
    private GenerationFormType formType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    protected ReplicateModel() {
        // richiesto da JPA
    }

    public ReplicateModel(String owner, String name, String version, String description, GenerationFormType formType, int sortOrder,
                          Instant createdAt) {
        this.owner = owner;
        this.name = name;
        this.version = version;
        this.description = description;
        this.formType = formType;
        this.sortOrder = sortOrder;
        this.active = true;
        this.createdAt = createdAt;
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+");

    /**
     * La sorgente di un LoRA anagrafato letta come modello Replicate: solo la forma {@code owner/nome} (niente URL, versioni o
     * file {@code .safetensors}) e' implicitamente un modello Replicate. Vuoto per ogni altra sorgente. L'owner non ha mai un punto
     * (un username Replicate e' alfanumerico con trattini): cosi' restano fuori per costruzione i riferimenti con host, come
     * {@code huggingface.co/owner/model}, {@code hf.co/...} e {@code civitai.com/models/123}, anche se brevi (due segmenti:
     * {@code huggingface.co/owner}); {@code civitai:123} e' escluso dai due punti.
     */
    public static Optional<String> identifierOfSource(String source) {
        if (source == null) {
            return Optional.empty();
        }
        String clean = source.strip();
        if (!IDENTIFIER.matcher(clean).matches() || clean.toLowerCase(java.util.Locale.ROOT).endsWith(".safetensors")) {
            return Optional.empty();
        }
        return Optional.of(clean);
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    /** "owner/name": la forma richiesta da ReplicateClient.createPrediction, usata anche come value/label del combobox modello. */
    public String getIdentifier() {
        return owner + "/" + name;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public GenerationFormType getFormType() {
        return formType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
