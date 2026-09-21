package org.dual.replicate.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Un turno persistito della conversazione di /deep-chat (vedi CLAUDE.md,
 * punto 3 dello Scopo). Un'unica conversazione continua, non multi-
 * utente (come il resto dell'app: {@link Generation} non ha un owner) —
 * niente tabella "conversazione" separata come nello schema rimosso in
 * V2: qui basta l'elenco messaggi, azzerabile per intero dal controllo
 * di reset in UI (vedi DeepChatService.resetHistory).
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChatMessageRole role;

    @Lob
    @Column(nullable = false)
    private String content;

    /**
     * Generazione immagine prodotta in questo turno, se presente. EAGER
     * perche' la tabella resta piccola (azzerata dal reset) e va sempre
     * letta insieme al messaggio per ricostruire l'allegato immagine
     * mostrato da deep-chat al ripristino della cronologia
     * (DeepChatController): con open-in-view=false un fetch LAZY
     * andrebbe in LazyInitializationException fuori dalla transazione
     * del repository.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "generation_id")
    private Generation generation;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
        // richiesto da JPA
    }

    public ChatMessage(ChatMessageRole role, String content, Generation generation) {
        this.role = role;
        this.content = content;
        this.generation = generation;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ChatMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Generation getGeneration() {
        return generation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
