package org.dual.replicate.core.tokens.port.in;

import java.time.LocalDate;
import java.util.List;

/**
 * CRUD dei token API salvati (cifrati nel DB) e loro risoluzione in chiaro per chi li usa. Il token vive in chiaro solo dentro
 * l'implementazione: la UI vede {@link TokenView} (nome, suffisso, scadenza, stato) e i chiamanti scelgono il token per ID.
 * Mai il segreto in log, eventi, toast o modello Thymeleaf. Il provider e' un nome ({@code ITokenProviderCatalog}), non un enum.
 */
public interface IApiTokens {

    int MAX_NAME = 60;
    int MAX_TOKEN = 500;

    /** Stato rispetto alla scadenza: OK (nessuna o lontana), EXPIRING (entro la soglia), EXPIRED (superata). */
    enum Status { OK, EXPIRING, EXPIRED }

    /** Vista per la UI: niente segreto, solo il suffisso per riconoscerlo. */
    record TokenView(Long id, String provider, String name, String hint, LocalDate expiresAt, Status status) {
    }

    /** {@code false} se manca la chiave di cifratura: la pagina /tokens lo segnala e creare/modificare e' rifiutato. */
    boolean isConfigured();

    int warningDays();

    List<TokenView> list();

    /** Token del provider, per le select delle form (nome + scadenza, mai il segreto). */
    List<TokenView> options(String provider);

    TokenView get(Long id);

    TokenView create(String provider, String name, String token, LocalDate expiresAt);

    /** {@code token} vuoto/null = lascia il token com'e'. Rinnovare la scadenza toglie dalla campanella gli avvisi di questo token. */
    TokenView update(Long id, String name, String token, LocalDate expiresAt);

    void delete(Long id);

    /** Plaintext del token scelto. Inesistente o scaduto: rifiuto atteso ({@code TokenException} REJECTED). */
    String resolve(Long id, String provider);

    /** Controllo di scadenza di tutti i token (job periodico, avvio): un avviso per ogni token scaduto o in scadenza. */
    int checkExpiries();
}
