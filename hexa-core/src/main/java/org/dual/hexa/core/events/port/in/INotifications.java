package org.dual.hexa.core.events.port.in;

/**
 * Notifiche EFFIMERE all'utente (toast di successo), su qualunque pagina aperta: l'esito positivo di qualcosa che e' finito in
 * background (una generazione, un training, un import). Non e' il registro degli eventi: niente righe in {@link ISystemEvents}, niente
 * campanella, niente persistenza; chi non ha tab aperte non le vede (come ogni evento SSE). Per errori e avvisi, che devono restare
 * consultabili, si usa {@link ISystemEvents}.
 *
 * <p>La consegna passa dall'SSE {@code GET /events} (evento {@code notice}); il client mostra il toast con {@code fragments/core/toast.html}.
 * Non lancia mai verso il chiamante.
 */
public interface INotifications {

    /**
     * Un toast di successo.
     *
     * @param key     identifica la notifica per la dedupe lato client (la stessa chiave non si mostra due volte), es. {@code generation-42}
     * @param message gia' tradotto (il core non conosce i testi dell'host)
     * @param path    path dell'app (senza context path, es. {@code /generations/42}) a cui punta il link «Apri»; {@code null} = nessun link
     */
    void success(String key, String message, String path);
}
