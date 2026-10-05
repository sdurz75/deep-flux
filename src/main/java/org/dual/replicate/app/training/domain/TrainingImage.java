package org.dual.replicate.app.training.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    /**
     * Sostituisce il ritaglio: {@code croppedFilename} e' il nuovo file (che va nello zip) e il rettangolo, in pixel dell'ORIGINALE, serve a riaprire
     * l'editor dov'era. Ritorna il ritaglio PRECEDENTE da eliminare, se c'era: l'originale non lo e' mai (si rifa' sempre il ritaglio da lui).
     */
    public Optional<String> applyCrop(String croppedFilename, int x, int y, int width, int height) {
        Optional<String> replaced = previousCrop();
        this.filename = croppedFilename;
        this.cropX = x;
        this.cropY = y;
        this.cropW = width;
        this.cropH = height;
        return replaced;
    }

    /** Torna all'originale: ritorna il ritaglio da eliminare, se c'era. */
    public Optional<String> clearCrop() {
        Optional<String> replaced = previousCrop();
        this.filename = originalFilename;
        this.cropX = null;
        this.cropY = null;
        this.cropW = null;
        this.cropH = null;
        return replaced;
    }

    /** Il file del ritaglio attuale, se e' un file a se': senza ritaglio {@code filename} E' l'originale e non va mai eliminato per questo. */
    private Optional<String> previousCrop() {
        return filename.equals(originalFilename) ? Optional.empty() : Optional.of(filename);
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

    // --- didascalia ---------------------------------------------------------------------------------------------
    //
    // Regola unica: una didascalia scritta a mano ({@code MANUAL}) non la sovrascrive mai un lavoro automatico, che quando torna controlla di avere
    // ancora diritto di scrivere (stessa immagine, ancora PENDING, non diventata MANUAL nel frattempo): la risposta del modello di visione arriva
    // dopo secondi, in cui l'utente puo' aver scritto, ritagliato o eliminato.

    public boolean isCaptionPending() {
        return captionStatus == CaptionStatus.PENDING;
    }

    /**
     * Chiede la didascalia automatica (immagine nuova, ritaglio rifatto, "Rigenera"): lo stato diventa {@code PENDING}. Il testo attuale resta finche' non
     * arriva il nuovo, cosi' un fallimento non lo perde. Una didascalia a mano si rispetta, salvo {@code overwriteManual} (richiesta esplicita
     * dell'utente: da quel momento e' di nuovo "da scrivere" a macchina).
     *
     * @return {@code false} se non si e' toccato nulla (didascalia a mano e nessuna richiesta esplicita)
     */
    public boolean requestAutoCaption(boolean overwriteManual) {
        if (captionSource == CaptionSource.MANUAL) {
            if (!overwriteManual) {
                return false;
            }
            captionSource = CaptionSource.NONE;
        }
        captionStatus = CaptionStatus.PENDING;
        return true;
    }

    /**
     * Scrive il risultato del lavoro automatico, se ne ha ancora diritto: {@code captionedFilename} e' il file che il lavoro ha guardato, e se nel frattempo
     * l'immagine e' stata ritagliata di nuovo la didascalia non vale piu' per quella attuale.
     *
     * @return {@code false} se il risultato e' stato scartato
     */
    public boolean applyAutoCaption(String captionedFilename, String text) {
        if (!mayReceiveAutoCaption(captionedFilename)) {
            return false;
        }
        this.caption = text;
        this.captionSource = CaptionSource.AUTO;
        this.captionStatus = CaptionStatus.DONE;
        return true;
    }

    /** Il lavoro automatico non ha prodotto una didascalia: stessa guardia di {@link #applyAutoCaption}; il testo precedente, se c'era, resta. */
    public boolean failAutoCaption(String captionedFilename) {
        if (!mayReceiveAutoCaption(captionedFilename)) {
            return false;
        }
        this.captionStatus = CaptionStatus.FAILED;
        return true;
    }

    private boolean mayReceiveAutoCaption(String captionedFilename) {
        return captionStatus == CaptionStatus.PENDING && captionSource != CaptionSource.MANUAL && filename.equals(captionedFilename);
    }

    /** La didascalia scritta (o corretta) a mano: {@code text} e' gia' ripulito, vuoto = nessuna didascalia. Chiude anche un lavoro automatico in corso. */
    public void writeCaption(String text) {
        boolean empty = text == null || text.isEmpty();
        this.caption = empty ? null : text;
        this.captionSource = empty ? CaptionSource.NONE : CaptionSource.MANUAL;
        this.captionStatus = CaptionStatus.DONE;
    }

    /** Ha una didascalia che non nomina la trigger word (senza distinguere maiuscole)? Senza didascalia: no, non c'e' nulla a cui aggiungerla. */
    public boolean isCaptionMissingTrigger(String triggerWord) {
        return caption != null && !caption.isBlank() && !caption.toLowerCase(Locale.ROOT).contains(triggerWord.toLowerCase(Locale.ROOT));
    }

    /** Mette la trigger word davanti a una didascalia che non la nomina; l'origine (automatica o a mano) non cambia. */
    public boolean prependTriggerWord(String triggerWord) {
        if (!isCaptionMissingTrigger(triggerWord)) {
            return false;
        }
        this.caption = triggerWord + ", " + caption;
        return true;
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
