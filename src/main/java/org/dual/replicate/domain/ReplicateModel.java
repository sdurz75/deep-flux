package org.dual.replicate.domain;

import java.time.Instant;

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
 * ({@link #formType}, vedi org.dual.replicate.service.GenerationParameterHandler).
 * Sostituisce il fetch live da Replicate (GET /collections/{slug},
 * GET /models/{owner}/{name}) che alimentava la vecchia
 * ReplicateModelCatalog: {@link #version} e' ora il valore censito qui,
 * non piu' risolto ad ogni avvio contro "l'ultima versione pubblicata".
 * Nessuna entity modifica mai una riga a runtime: solo letture.
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
