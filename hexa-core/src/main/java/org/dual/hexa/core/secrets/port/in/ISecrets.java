package org.dual.hexa.core.secrets.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.dual.hexa.core.secrets.domain.SecretType;

/**
 * CRUD dei segreti salvati (cifrati nel DB) e loro risoluzione in chiaro per chi li usa. UN solo tipo di entita': token API di un servizio, password,
 * segreto di un client OAuth2... distinti dal {@link SecretType}. Il valore vive in chiaro solo dentro l'implementazione: la UI vede {@link SecretView}
 * (nome, suffisso, scadenza, stato) e i chiamanti scelgono il segreto per ID. Mai il valore in log, eventi, toast o modello Thymeleaf.
 */
public interface ISecrets {

    int MAX_NAME = 60;
    int MAX_VALUE = 500;

    /** Stato rispetto alla scadenza: OK (nessuna o lontana), EXPIRING (entro la soglia), EXPIRED (superata). */
    enum Status { OK, EXPIRING, EXPIRED }

    /** Vista per la UI: niente valore, solo il suffisso per riconoscerlo. {@code managed}: appartiene a un modulo (campo SECRET), vedi {@link SecretType}. */
    record SecretView(Long id, String type, String name, String hint, LocalDate expiresAt, Status status, boolean managed) {
    }

    /** {@code false} se manca la chiave di cifratura: la pagina /secrets lo segnala e creare/modificare e' rifiutato. */
    boolean isConfigured();

    int warningDays();

    /** I tipi registrati (core, moduli, app), nell'ordine in cui si mostrano. */
    List<SecretType> types();

    List<SecretView> list();

    /** I segreti di un tipo, per le select delle form (nome + scadenza, mai il valore). */
    List<SecretView> options(String type);

    SecretView get(Long id);

    /** Crea a mano (pagina /secrets): un tipo {@code managed} o sconosciuto e' rifiutato. */
    SecretView create(String type, String name, String value, LocalDate expiresAt);

    /** {@code value} vuoto/null = lascia il valore com'e'. Rinnovare la scadenza toglie dalla campanella gli avvisi di questo segreto. Rifiuta un tipo {@code managed}. */
    SecretView update(Long id, String name, String value, LocalDate expiresAt);

    /** Rifiuta un tipo {@code managed}: lo cancella il modulo che lo possiede. */
    void delete(Long id);

    /** Valore in chiaro del segreto scelto. Inesistente, di un altro tipo o scaduto: rifiuto atteso ({@code SecretException} REJECTED). */
    String resolve(Long id, String type);

    // --- per i moduli che possiedono i propri segreti (campi SECRET di IConfigModule) -------------------------------

    /** Crea o sostituisce il segreto {@code (type, name)}, anche se il tipo e' {@code managed}. */
    SecretView store(String type, String name, String value);

    Optional<SecretView> find(String type, String name);

    /** Valore in chiaro di {@code (type, name)}, vuoto se non esiste. */
    Optional<String> resolveByName(String type, String name);

    /** Cancella il segreto {@code (type, name)} (anche se {@code managed}). @return se esisteva. */
    boolean deleteByName(String type, String name);

    /** Cancella i segreti di {@code type} il cui nome inizia con {@code prefix} (anche se {@code managed}). @return quanti. */
    int deleteByNamePrefix(String type, String prefix);

    /**
     * Quanti segreti salvati NON si decifrano con la chiave attuale (chiave diversa da quella con cui furono salvati, o assente): dopo il
     * ripristino di un backup su un sistema con un'altra chiave vanno reinseriti. Non lancia e non espone alcun valore.
     */
    int undecryptableCount();

    /** Controllo di scadenza di tutti i segreti (job periodico, avvio): un avviso per ogni segreto scaduto o in scadenza. */
    int checkExpiries();
}
