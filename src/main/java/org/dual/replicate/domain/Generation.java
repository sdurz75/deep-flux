package org.dual.replicate.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

/**
 * Una richiesta di generazione immagine su Replicate: tiene insieme il
 * prompt e i parametri usati (per poterli riconsultare in galleria), lo
 * stato della prediction remota e, una volta pronta, il nome del file
 * immagine salvato localmente sotto storage.images-dir.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GenerationStatus status;

    /** Nome file sotto storage.images-dir, valorizzato solo a status SUCCEEDED. */
    private String imageFilename;

    @Lob
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    protected Generation() {
        // richiesto da JPA
    }

    public Generation(String externalId, String model, String version, String prompt, String parametersJson) {
        this.externalId = externalId;
        this.model = model;
        this.version = version;
        this.prompt = prompt;
        this.parametersJson = parametersJson;
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

    public GenerationStatus getStatus() {
        return status;
    }

    public void setStatus(GenerationStatus status) {
        this.status = status;
    }

    public String getImageFilename() {
        return imageFilename;
    }

    public void setImageFilename(String imageFilename) {
        this.imageFilename = imageFilename;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
