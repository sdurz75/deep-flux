package org.dual.replicate.app.chat.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Una conversazione di /deep-chat: raggruppa i turni (ChatMessage) che
 * l'utente vede/riprende insieme dalla sidebar. {@code title} resta
 * null finche' l'utente non la rinomina esplicitamente o finche' non ha
 * un primo turno utente da cui ChatService puo' derivarlo — mai un
 * letterale di default persistito (es. "Nuova conversazione"), altrimenti
 * quel testo resterebbe congelato nella lingua di chi l'ha generato,
 * come il limite gia' noto e accettato per Generation.errorMessage (vedi
 * CLAUDE.md, sezione i18n). Il fallback per una conversazione ancora
 * senza titolo e' quindi risolto lato template con una chiave i18n, non
 * qui.
 */
@Entity
@Table(name = "chat_conversation")
public class ChatConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Stato grezzo del form di generazione di questa conversazione (JSON: modello e parametri, la chat non lo interpreta): lo
     * salva il client (POST /deep-chat/{id}/settings) e la pagina lo rimette nel form alla selezione. {@code null} = conversazione
     * precedente alla migrazione (adotta il form del browser); {@code "{}"} = default del catalogo (ogni conversazione nuova).
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String generationSettingsJson;

    /**
     * Nessun costruttore protetto separato "richiesto da JPA" come nelle
     * altre entity: qui l'unico costruttore e' gia' senza argomenti
     * (nessun campo obbligatorio da passare, a differenza di Generation/
     * ChatMessage), quindi JPA riusa direttamente questo — pubblico
     * perche' serve anche alla creazione applicativa "vera".
     */
    public ChatConversation() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.generationSettingsJson = EMPTY_SETTINGS;
    }

    /** Nessuna configurazione propria: il form riparte dai default del catalogo. */
    public static final String EMPTY_SETTINGS = "{}";

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getGenerationSettingsJson() {
        return generationSettingsJson;
    }

    /** Non chiama touch(): cambiare un parametro non e' attivita' di chat, non deve riordinare la sidebar. */
    public void setGenerationSettingsJson(String generationSettingsJson) {
        this.generationSettingsJson = generationSettingsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Aggiorna updatedAt a ora: usato quando arriva un nuovo turno utente, per ordinare la sidebar per recenza. */
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
