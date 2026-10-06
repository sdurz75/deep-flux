package org.dual.replicate.core.chat.port.in;

import java.util.List;
import java.util.Optional;

import org.dual.replicate.core.chat.domain.ChatConversation;
import org.dual.replicate.core.chat.domain.ChatMessage;

/** Le conversazioni di /deep-chat: elenco, creazione, rinomina, cancellazione, cronologia. */
public interface IChatConversations {

    /** Piu' di recente attive prima. */
    List<ChatConversation> list();

    Optional<ChatConversation> find(Long id);

    /** La piu' di recente attiva, o una nuova vuota se non ne esiste ancora nessuna (primo avvio). */
    ChatConversation resolveDefault();

    ChatConversation create();

    /** Un titolo vuoto/di soli spazi diventa {@code null} (la UI mostra allora il titolo di default). */
    ChatConversation rename(Long id, String title);

    /**
     * Aggiunge un tag utente (normalizzato da {@code Tags}; uno vuoto e' ignorato, oltre {@code Tags.MAX_PER_ENTITY} e' un rifiuto).
     * Come {@link #rename}: non riordina la sidebar.
     */
    ChatConversation addTag(Long id, String tag);

    /** Toglie un tag utente (assente = nessun effetto). */
    ChatConversation removeTag(Long id, String tag);

    /**
     * Salva lo stato grezzo del form di generazione della conversazione (JSON-oggetto di modello e parametri: la chat non lo interpreta,
     * lo rilegge solo il form alla selezione). Non cambia l'ordine "piu' recente prima".
     *
     * @throws IllegalArgumentException se la conversazione non esiste, o se {@code json} non e' un oggetto JSON o supera
     *                                  {@link #MAX_SETTINGS_BYTES}
     */
    void saveGenerationSettings(Long id, String json);

    /** Tetto del JSON del form salvato: i parametri di un form-type sono poche centinaia di byte, oltre e' un abuso/un errore. */
    int MAX_SETTINGS_BYTES = 16 * 1024;

    /** Cancella i turni e la conversazione; non tocca le generazioni (ciclo di vita indipendente). */
    void delete(Long id);

    /** I turni persistiti, in ordine cronologico. */
    List<ChatMessage> history(Long conversationId);
}
