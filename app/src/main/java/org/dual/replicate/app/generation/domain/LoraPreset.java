package org.dual.replicate.app.generation.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * LoRA anagrafato (V2, {@code LoraPresetService}): sorgente ({@code owner/nome}, URL HuggingFace/CivitAI o .safetensors),
 * intensita' predefinita e, facoltative, trigger words e nota. E' solo un aiuto di compilazione delle form di flux-dev-lora:
 * le generazioni non lo referenziano.
 */
@Entity
@Table(name = "lora_preset")
public class LoraPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 500)
    private String source;

    @Column(nullable = false)
    private double scale;

    @Column(length = 500)
    private String triggerWords;

    @Column(length = 500)
    private String note;

    /** Token API di default (riferimento a {@code api_token}, FK {@code ON DELETE SET NULL}); {@code null} = nessuno. */
    @Column
    private Long defaultTokenId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected LoraPreset() {
        // richiesto da JPA
    }

    public LoraPreset(String name, String source, double scale, String triggerWords, String note, Instant now) {
        this(name, source, scale, triggerWords, note, null, now);
    }

    public LoraPreset(String name, String source, double scale, String triggerWords, String note, Long defaultTokenId, Instant now) {
        this.name = name;
        this.defaultTokenId = defaultTokenId;
        this.source = source;
        this.scale = scale;
        this.triggerWords = triggerWords;
        this.note = note;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String source, double scale, String triggerWords, String note, Long defaultTokenId, Instant now) {
        this.name = name;
        this.defaultTokenId = defaultTokenId;
        this.source = source;
        this.scale = scale;
        this.triggerWords = triggerWords;
        this.note = note;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public double getScale() {
        return scale;
    }

    public String getTriggerWords() {
        return triggerWords;
    }

    public String getNote() {
        return note;
    }

    public Long getDefaultTokenId() {
        return defaultTokenId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "LoraPreset[id=" + id + ", name=" + name + "]";
    }
}
