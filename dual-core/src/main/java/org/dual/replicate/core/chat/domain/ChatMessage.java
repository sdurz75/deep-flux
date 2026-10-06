package org.dual.replicate.core.chat.domain;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un turno persistito di una conversazione di /deep-chat (vedi
 * CLAUDE.md, punto 3 dello Scopo). Da quando /deep-chat supporta piu'
 * conversazioni (ognuna una riga {@link ChatConversation}, elencabile/
 * rinominabile/eliminabile dalla sidebar), ogni messaggio appartiene
 * esattamente a una di esse — l'app resta comunque non multi-utente
 * (come il resto dell'app: Generation non ha un owner), solo
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

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String content;

    /**
     * Conversazione a cui appartiene questo turno. LAZY (a differenza di
     * {@code outcomeRef} sotto): nessun codice legge mai
     * {@code getConversation()} — l'accesso e' sempre per query gia'
     * filtrata per conversazione (vedi ChatMessageRepository), mai
     * navigando l'associazione da un'istanza gia' caricata, quindi non
     * serve ne' un fetch EAGER ne' un getter.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ChatConversation conversation;

    /**
     * Riferimento OPACO all'esito prodotto in questo turno, se presente (oggi l'id di una generazione): per la chat e' solo un
     * {@code Long}, chi lo interpreta (cronologia, ripristino dell'allegato) lo legge dal servizio del proprio dominio, in blocco per
     * id. Colonna {@code outcome_ref}; la FK verso {@code generation} ON DELETE SET NULL e' dell'app (non del core), quindi la
     * cancellazione della generazione lascia il turno e azzera il riferimento.
     */
    @Column(name = "outcome_ref")
    private Long outcomeRef;

    /**
     * True per il turno ASSISTANT scritto quando la chiamata all'LLM fallisce (vedi V18,
     * ChatService#reply): tiene la cronologia coerente (nessun turno USER senza risposta) e
     * viene mostrato con stile d'errore. Non entra mai nel contesto inviato all'LLM
     * (ChatHistoryBuilder lo salta).
     */
    @Column(name = "error", nullable = false)
    private boolean error;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChatMessage() {
        // richiesto da JPA
    }

    public ChatMessage(ChatConversation conversation, ChatMessageRole role, String content, Long outcomeRef) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.outcomeRef = outcomeRef;
        this.createdAt = Instant.now();
    }

    /** Turno d'errore ASSISTANT (vedi {@link #isError()}). */
    public static ChatMessage errorTurn(ChatConversation conversation, String content) {
        ChatMessage message = new ChatMessage(conversation, ChatMessageRole.AI, content, null);
        message.error = true;
        return message;
    }

    public boolean isError() {
        return error;
    }

    public Long getId() {
        return id;
    }

    public ChatConversation getConversation() {
        return conversation;
    }

    public ChatMessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Long getOutcomeRef() {
        return outcomeRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
