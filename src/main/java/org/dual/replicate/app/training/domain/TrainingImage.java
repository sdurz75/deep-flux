package org.dual.replicate.app.training.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un'immagine di un dataset di addestramento. Possiede i suoi binari (nessuna dedup, come ogni altro file dell'app): {@link #getFilename()} e' quella che
 * andra' nello zip (l'originale o il suo ritaglio), {@link #getOriginalFilename()} l'originale caricato, da cui si puo' sempre rifare il ritaglio. Finche' non
 * c'e' un ritaglio i due nomi coincidono e il file e' UNO solo.
 */
@Entity
@Table(name = "training_image")
public class TrainingImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false)
    private TrainingDataset dataset;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String originalFilename;

    /** Il nome del file sul disco dell'utente: solo da mostrare, mai usato come nome nello storage ne' nello zip. */
    @Column
    private String originalName;

    /**
     * Rettangolo di ritaglio sull'ORIGINALE, in pixel; tutti null = non ritagliata. Serve a riaprire l'editor dove era rimasto.
     * I nomi delle colonne sono ESPLICITI: la strategia di naming di Spring non mette l'underscore davanti a una singola maiuscola finale
     * ({@code cropH} diventerebbe {@code croph}, non {@code crop_h}) e la validazione dello schema fa fallire l'avvio.
     */
    @Column(name = "crop_x")
    private Integer cropX;

    @Column(name = "crop_y")
    private Integer cropY;

    @Column(name = "crop_w")
    private Integer cropW;

    @Column(name = "crop_h")
    private Integer cropH;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String caption;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CaptionSource captionSource = CaptionSource.NONE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CaptionStatus captionStatus = CaptionStatus.DONE;

    @Column(nullable = false)
    private Instant createdAt;

    protected TrainingImage() {
        // richiesto da JPA
    }

    TrainingImage(TrainingDataset dataset, int sortOrder, String filename, String originalFilename, String originalName, Instant now) {
        this.dataset = dataset;
        this.sortOrder = sortOrder;
        this.filename = filename;
        this.originalFilename = originalFilename;
        this.originalName = originalName;
        this.createdAt = now;
    }

    /** Copia per un altro dataset (clone, snapshot): i nomi dei file sono gia' quelli delle COPIE, la didascalia e il ritaglio si portano dietro. */
    TrainingImage copyOnto(TrainingDataset target, int sortOrder, String filename, String originalFilename, Instant now) {
        TrainingImage copy = new TrainingImage(target, sortOrder, filename, originalFilename, originalName, now);
        copy.cropX = cropX;
        copy.cropY = cropY;
        copy.cropW = cropW;
        copy.cropH = cropH;
        copy.caption = caption;
        copy.captionSource = captionSource;
        // Un lavoro di caption in corso sull'originale non e' in corso sulla copia: niente PENDING ereditato.
        copy.captionStatus = captionStatus == CaptionStatus.PENDING ? CaptionStatus.DONE : captionStatus;
        return copy;
    }

    public boolean isCropped() {
        return cropW != null && cropH != null;
    }

    /** I file che questa riga possiede (uno solo se non e' ritagliata): sono quelli da eliminare con la riga. */
    public List<String> ownedFilenames() {
        List<String> names = new ArrayList<>(2);
        names.add(filename);
        if (!originalFilename.equals(filename)) {
            names.add(originalFilename);
        }
        return names;
    }

    public Long getId() {
        return id;
    }

    public TrainingDataset getDataset() {
        return dataset;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getFilename() {
        return filename;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getOriginalName() {
        return originalName;
    }

    public Integer getCropX() {
        return cropX;
    }

    public Integer getCropY() {
        return cropY;
    }

    public Integer getCropW() {
        return cropW;
    }

    public Integer getCropH() {
        return cropH;
    }

    public String getCaption() {
        return caption;
    }

    public CaptionSource getCaptionSource() {
        return captionSource;
    }

    public CaptionStatus getCaptionStatus() {
        return captionStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "TrainingImage[id=" + id + ", filename=" + filename + "]";
    }
}
