package org.dual.replicate.app.training.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Dataset di addestramento di un LoRA: una BOZZA persistente (immagini, didascalie, configurazione) che si riprende, si modifica e da cui si lancia un
 * training. {@link #isFrozen()} e' lo snapshot di un training gia' lanciato: sola lettura, fuori dall'elenco delle bozze. Eliminare una bozza non tocca i
 * training, che hanno il proprio snapshot (con i propri file).
 *
 * <p>{@link #getSourceDatasetId()} e' la provenienza di un clone o di uno snapshot: un {@code Long} senza FK, perche' la bozza di origine puo' sparire.
 */
@Entity
@Table(name = "training_dataset")
public class TrainingDataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 40)
    private String triggerWord;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoraType loraType;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private boolean frozen;

    @Column
    private Long sourceDatasetId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Ogni modifica al contenuto aggiorna anche {@code updatedAt}, quindi la versione: una copia vecchia non puo' sovrascrivere in silenzio. */
    @Version
    private long version;

    // EAGER perche' open-in-view e' spento e un dataset si legge sempre con le sue immagini (al massimo qualche decina).
    @OneToMany(mappedBy = "dataset", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC, id ASC")
    private List<TrainingImage> images = new ArrayList<>();

    protected TrainingDataset() {
        // richiesto da JPA
    }

    public TrainingDataset(String name, String triggerWord, LoraType loraType, String note, Instant now) {
        this(name, triggerWord, loraType, note, false, null, now);
    }

    public TrainingDataset(String name, String triggerWord, LoraType loraType, String note, boolean frozen, Long sourceDatasetId, Instant now) {
        this.name = name;
        this.triggerWord = triggerWord;
        this.loraType = loraType;
        this.note = note;
        this.frozen = frozen;
        this.sourceDatasetId = sourceDatasetId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String triggerWord, LoraType loraType, String note, Instant now) {
        this.name = name;
        this.triggerWord = triggerWord;
        this.loraType = loraType;
        this.note = note;
        this.updatedAt = now;
    }

    /** Segna una modifica al contenuto (immagini, didascalie): la bozza torna in cima all'elenco. */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    /** Aggiunge un'immagine in coda, con {@code filename} come originale (senza ritaglio i due nomi coincidono). */
    public TrainingImage addImage(String filename, String originalName, Instant now) {
        TrainingImage image = new TrainingImage(this, nextSortOrder(), filename, filename, originalName, now);
        images.add(image);
        updatedAt = now;
        return image;
    }

    /** Aggiunge una copia di {@code source} (di un altro dataset) con i nomi dei file gia' copiati. */
    public TrainingImage addCopyOf(TrainingImage source, String filename, String originalFilename, Instant now) {
        TrainingImage copy = source.copyOnto(this, nextSortOrder(), filename, originalFilename, now);
        images.add(copy);
        return copy;
    }

    /** Toglie l'immagine {@code imageId}; vuoto se non e' di questo dataset. Chi chiama elimina i file di {@link TrainingImage#ownedFilenames()}. */
    public Optional<TrainingImage> removeImage(Long imageId, Instant now) {
        Optional<TrainingImage> found = findImage(imageId);
        found.ifPresent(image -> {
            images.remove(image);
            updatedAt = now;
        });
        return found;
    }

    public Optional<TrainingImage> findImage(Long imageId) {
        return images.stream().filter(i -> i.getId() != null && i.getId().equals(imageId)).findFirst();
    }

    /** Tutti i file posseduti dal dataset, senza doppioni (per eliminarli con lui). */
    public Set<String> ownedFilenames() {
        Set<String> names = new LinkedHashSet<>();
        images.forEach(image -> names.addAll(image.ownedFilenames()));
        return names;
    }

    private int nextSortOrder() {
        return images.stream().mapToInt(TrainingImage::getSortOrder).max().orElse(-1) + 1;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTriggerWord() {
        return triggerWord;
    }

    public LoraType getLoraType() {
        return loraType;
    }

    public String getNote() {
        return note;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public Long getSourceDatasetId() {
        return sourceDatasetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<TrainingImage> getImages() {
        return Collections.unmodifiableList(images);
    }

    @Override
    public String toString() {
        return "TrainingDataset[id=" + id + ", name=" + name + "]";
    }
}
