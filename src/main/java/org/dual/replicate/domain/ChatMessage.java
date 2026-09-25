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
 * Un turno persistito di una conversazione di /deep-chat (vedi
 * CLAUDE.md, punto 3 dello Scopo). Da quando /deep-chat supporta piu'
 * conversazioni (ognuna una riga {@link ChatConversation}, elencabile/
 * rinominabile/eliminabile dalla sidebar), ogni messaggio appartiene
 * esattamente a una di esse — l'app resta comunque non multi-utente
 * (come il resto dell'app: {@link Generation} non ha un owner), solo
 * multi-conversazione per lo stesso singolo utente.
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
     * Conversazione a cui appartiene questo turno. LAZY (a differenza di
     * {@code generation} sotto): nessun codice legge mai
     * {@code getConversation()} — l'accesso e' sempre per query gia'
     * filtrata per conversazione (vedi ChatMessageRepository), mai
     * navigando l'associazione da un'istanza gia' caricata, quindi non
     * serve ne' un fetch EAGER ne' un getter.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    /**
     * Generazione immagine prodotta in questo turno, se presente. EAGER
     * perche' va sempre letta insieme al messaggio per ricostruire
     * l'allegato immagine mostrato da deep-chat al ripristino della
     * cronologia (DeepChatController): con open-in-view=false un fetch
     * LAZY andrebbe in LazyInitializationException fuori dalla
     * transazione del repository.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "generation_id")
    private Generation generation;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
        // richiesto da JPA
    }

    public ChatMessage(ChatConversation conversation, ChatMessageRole role, String content, Generation generation) {
        this.conversation = conversation;
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
