package org.dual.replicate.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OrderColumn;

/**
 * Una richiesta di generazione immagine su Replicate: tiene insieme il
 * prompt e i parametri usati (per poterli riconsultare in galleria), lo
 * stato della prediction remota e, una volta pronta, i nomi dei file
 * immagine salvati localmente sotto storage.images-dir (uno o piu', a
 * seconda del parametro num_outputs).
 */
@Entity
public class Generation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id della prediction lato Replicate (es. "ufawqhfynnddngldkgtslldrkq"). */
    @Column(nullable = false)
    private String externalId;

    /** "owner/name" del modello Replicate usato. */
    @Column(nullable = false)
    private String model;

    /** Version hash pinnata, se l'utente l'ha specificata. Puo' essere null. */
    private String version;

    @Lob
    @Column(nullable = false)
    private String prompt;

    /** JSON dei parametri extra (oltre al prompt) passati come input. Puo' essere null. */
    @Lob
    private String parametersJson;

    /**
     * Seed effettivamente usato per questa generazione (vedi migrazione
     * V8), non solo sepolto dentro {@link #parametersJson}: valorizzato
     * subito in create() se l'utente lo ha specificato esplicitamente,
     * altrimenti (Replicate ne genera uno casuale) un tentativo best-effort
     * in refresh() prova a leggerlo dai log della prediction una volta
     * completata (vedi GenerationService). Puo' restare null se l'utente
     * non l'ha specificato e il modello non lo logga in un formato
     * riconoscibile.
     */
    private Long seed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationStatus status;

    /**
     * Nomi file sotto storage.images-dir, uno per immagine prodotta da
     * questa generazione (num_outputs puo' chiedere piu' di un'immagine
     * per richiesta), valorizzati solo a status SUCCEEDED. EAGER perche'
     * open-in-view e' disattivato (application.yml) e la collezione va
     * letta anche fuori dalla transazione che ha caricato l'entity
     * (ripristino cronologia di /deep-chat, rendering di galleria/stato)
     * — stesso motivo per cui ChatMessage.generation e' EAGER.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "generation_image", joinColumns = @JoinColumn(name = "generation_id"))
    @OrderColumn(name = "ordinal")
    @Column(name = "filename")
    private List<String> imageFilenames = new ArrayList<>();

    @Lob
    private String errorMessage;

    /**
     * Costo stimato in USD (vedi V13, ReplicatePricing): snapshot calcolato
     * al completamento, null se non stimabile (righe precedenti, fallite,
     * modello senza regola).
     */
    @Column(precision = 12, scale = 6)
    private BigDecimal costUsd;

    /**
     * Tipo di media prodotto (vedi V12): IMAGE per tutte le righe
     * preesistenti, VIDEO per i modelli video. I file restano comunque in
     * {@link #imageFilenames} (un mp4 e' un output singolo).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationKind kind = GenerationKind.IMAGE;

    /**
     * Generazione da cui questa e' stata derivata (img2video: l'immagine
     * animata), null altrimenti. Non e' una relazione JPA: la FK e' ON
     * DELETE SET NULL (vedi V12) e basta l'id per linkare il dettaglio.
     */
    private Long sourceGenerationId;

    /**
     * Conversazione di /deep-chat che ha avviato la generazione (null per il
     * form diretto): serve a ripristinare il placeholder di una generazione
     * ancora in corso quando la pagina viene ricaricata (vedi V11).
     */
    private Long conversationId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    protected Generation() {
        // richiesto da JPA
    }

    public Generation(String externalId, String model, String version, String prompt, String parametersJson) {
        this(externalId, model, version, prompt, parametersJson, null);
    }

    public Generation(String externalId, String model, String version, String prompt, String parametersJson, Long seed) {
        this.externalId = externalId;
        this.model = model;
        this.version = version;
        this.prompt = prompt;
        this.parametersJson = parametersJson;
        this.seed = seed;
        this.status = GenerationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getModel() {
        return model;
    }

    public String getVersion() {
        return version;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public GenerationStatus getStatus() {
        return status;
    }

    public void setStatus(GenerationStatus status) {
        this.status = status;
    }

    public List<String> getImageFilenames() {
        return imageFilenames;
    }

    public void setImageFilenames(List<String> imageFilenames) {
        this.imageFilenames = imageFilenames;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(BigDecimal costUsd) {
        this.costUsd = costUsd;
    }

    public GenerationKind getKind() {
        return kind;
    }

    public void setKind(GenerationKind kind) {
        this.kind = kind;
    }

    public boolean isVideo() {
        return kind == GenerationKind.VIDEO;
    }

    public Long getSourceGenerationId() {
        return sourceGenerationId;
    }

    public void setSourceGenerationId(Long sourceGenerationId) {
        this.sourceGenerationId = sourceGenerationId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public boolean isTerminal() {
        return status == GenerationStatus.SUCCEEDED || status == GenerationStatus.FAILED;
    }
}
