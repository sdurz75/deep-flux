package org.dual.replicate.app.training.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un training di un LoRA lanciato su Replicate ({@code replicate/fast-flux-trainer}). Ha il PROPRIO dataset congelato ({@link #getSnapshotDatasetId()}: le immagini
 * e le didascalie com'erano al lancio) e il proprio modello Replicate di destinazione. Eliminarlo elimina snapshot e file, NON il modello Replicate, il repo
 * HuggingFace ne' il preset. Il token HuggingFace non sta qui: si usa solo al lancio.
 */
@Entity
@Table(name = "training")
public class Training {

    /** Si tengono solo le ultime righe dei log: il trainer ne produce a migliaia e a noi serve vedere come sta andando. */
    public static final int MAX_LOG_CHARS = 20_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long snapshotDatasetId;

    @Column
    private Long sourceDatasetId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 40)
    private String triggerWord;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoraType loraType;

    @Column(nullable = false)
    private int imageCount;

    @Column(length = 100)
    private String externalId;

    @Column(length = 100)
    private String trainerVersion;

    @Column(length = 100)
    private String destinationOwner;

    @Column(length = 100)
    private String destinationName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrainingStatus status;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String logs;

    @Column(nullable = false)
    private int trainingSteps;

    @Column
    private Long seed;

    @Column(nullable = false)
    private boolean hfPublish;

    @Column(length = 200)
    private String hfRepoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HfStatus hfStatus = HfStatus.NONE;

    @Column
    private Long presetId;

    @Column
    private Double predictTimeSeconds;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant completedAt;

    protected Training() {
        // richiesto da JPA
    }

    /** Un training appena creato su Replicate: {@code externalId} e {@code status} sono quelli della risposta. */
    public Training(TrainingDataset snapshot, String externalId, TrainingStatus status, String trainerVersion, String destinationOwner,
                    String destinationName, String hfRepoId, Instant now) {
        this.snapshotDatasetId = snapshot.getId();
        this.sourceDatasetId = snapshot.getSourceDatasetId();
        this.name = snapshot.getName();
        this.triggerWord = snapshot.getTriggerWord();
        this.loraType = snapshot.getLoraType();
        this.imageCount = snapshot.getImages().size();
        this.externalId = externalId;
        this.status = status;
        this.trainerVersion = trainerVersion;
        this.destinationOwner = destinationOwner;
        this.destinationName = destinationName;
        this.trainingSteps = snapshot.getTrainingSteps();
        this.seed = snapshot.getSeed();
        this.hfPublish = snapshot.isHfPublish();
        this.hfRepoId = hfRepoId;
        this.hfStatus = snapshot.isHfPublish() ? HfStatus.PENDING : HfStatus.NONE;
        this.createdAt = now;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** Il modello Replicate di destinazione nella forma {@code owner/nome}. */
    public String destinationModel() {
        return destinationOwner + "/" + destinationName;
    }

    /** Un training in corso avanza (stato e log); i log si tagliano alla coda. */
    public void advance(TrainingStatus newStatus, String newLogs) {
        this.status = newStatus;
        this.logs = tail(newLogs);
    }

    public void succeed(String newLogs, Double predictTime, Instant now) {
        this.status = TrainingStatus.SUCCEEDED;
        this.logs = tail(newLogs);
        this.predictTimeSeconds = predictTime;
        this.completedAt = now;
    }

    public void fail(TrainingStatus terminal, String message, String newLogs, Double predictTime, Instant now) {
        this.status = terminal;
        this.errorMessage = message;
        if (newLogs != null) {
            this.logs = tail(newLogs);
        }
        if (predictTime != null) {
            this.predictTimeSeconds = predictTime;
        }
        this.completedAt = now;
    }

    public void setHfStatus(HfStatus hfStatus) {
        this.hfStatus = hfStatus;
    }

    public void setPresetId(Long presetId) {
        this.presetId = presetId;
    }

    private static String tail(String text) {
        if (text == null || text.length() <= MAX_LOG_CHARS) {
            return text;
        }
        String cut = text.substring(text.length() - MAX_LOG_CHARS);
        int newline = cut.indexOf('\n');
        return newline >= 0 && newline < cut.length() - 1 ? cut.substring(newline + 1) : cut;
    }

    public Long getId() {
        return id;
    }

    public Long getSnapshotDatasetId() {
        return snapshotDatasetId;
    }

    public Long getSourceDatasetId() {
        return sourceDatasetId;
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

    public int getImageCount() {
        return imageCount;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getTrainerVersion() {
        return trainerVersion;
    }

    public String getDestinationOwner() {
        return destinationOwner;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public TrainingStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getLogs() {
        return logs;
    }

    public int getTrainingSteps() {
        return trainingSteps;
    }

    public Long getSeed() {
        return seed;
    }

    public boolean isHfPublish() {
        return hfPublish;
    }

    public String getHfRepoId() {
        return hfRepoId;
    }

    public HfStatus getHfStatus() {
        return hfStatus;
    }

    public Long getPresetId() {
        return presetId;
    }

    public Double getPredictTimeSeconds() {
        return predictTimeSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public String toString() {
        return "Training[id=" + id + ", name=" + name + ", status=" + status + "]";
    }
}
