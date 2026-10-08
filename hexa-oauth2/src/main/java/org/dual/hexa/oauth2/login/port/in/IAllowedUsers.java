package org.dual.hexa.oauth2.login.port.in;

import java.util.List;
import org.dual.hexa.oauth2.login.domain.AllowKind;
import org.dual.hexa.oauth2.login.domain.AllowedEntry;

/** La lista degli utenti ammessi: le voci d'ambiente ({@code HX_OAUTH2_ALLOWED_*}, sempre valide, in sola lettura) e quelle salvate dalla UI. */
public interface IAllowedUsers {

    int MAX_VALUE = 255;

    List<AllowedEntry> list();

    /**
     * Aggiunge una voce (valore normalizzato: minuscolo, senza spazi; un dominio senza la chiocciola iniziale).
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException se il formato non e' valido o la voce esiste gia'
     */
    AllowedEntry add(AllowKind kind, String value);

    /**
     * Toglie una voce salvata.
     *
     * @throws org.dual.hexa.oauth2.login.domain.OAuthException se il cancello e' acceso ed e' l'ultima voce utile
     */
    void remove(Long id);
}
